package org.kohsuke.github.internal;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.io.IOUtils;
import org.kohsuke.github.*;
import org.kohsuke.github.connector.GitHubConnector;
import org.kohsuke.github.connector.GitHubConnectorRequest;
import org.kohsuke.github.connector.GitHubConnectorResponse;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

import javax.annotation.Nonnull;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.util.logging.Level.*;

/**
 * Adapts an HttpConnector to be usable as GitHubConnector.
 *
 * For internal use only.
 */
public final class GitHubConnectorHttpConnectorAdapter implements GitHubConnector, HttpConnector {

    /**
     * Internal for testing.
     */
    final HttpConnector httpConnector;

    /**
     * Constructor.
     *
     * @param httpConnector
     *            the HttpConnector to be adapted.
     */
    public GitHubConnectorHttpConnectorAdapter(HttpConnector httpConnector) {
        this.httpConnector = httpConnector;
    }

    /**
     * Creates a GitHubConnector for an HttpConnector.
     *
     * If a well-known static HttpConnector is passed, a corresponding static GitHubConnector is returned.
     *
     * @param connector
     *            the HttpConnector to be adapted.
     * @return a GitHubConnector that calls into the provided HttpConnector.
     */
    @Nonnull
    public static GitHubConnector adapt(@Nonnull HttpConnector connector) {
        GitHubConnector gitHubConnector;
        if (connector == HttpConnector.DEFAULT) {
            gitHubConnector = GitHubConnector.DEFAULT;
        } else if (connector == HttpConnector.OFFLINE) {
            gitHubConnector = GitHubConnector.OFFLINE;
        } else if (connector instanceof GitHubConnector) {
            gitHubConnector = (GitHubConnector) connector;
        } else {
            gitHubConnector = new GitHubConnectorHttpConnectorAdapter(connector);
        }
        return gitHubConnector;
    }

    @Nonnull
    public HttpURLConnection connect(URL url) throws IOException {
        return this.httpConnector.connect(url);
    }

    @Nonnull
    public GitHubConnectorResponse send(GitHubConnectorRequest request) throws IOException {
        HttpURLConnection connection;
        try {
            connection = setupConnection(this, request);
        } catch (IOException e) {
            // An error in here should be wrapped to bypass http exception wrapping.
            throw new GHIOException(e.getMessage(), e);
        }

        // HttpUrlConnection is nuts. This call opens the connection and gets a response.
        // Putting this on it's own line for ease of debugging if needed.
        int statusCode = connection.getResponseCode();
        Map<String, List<String>> headers = connection.getHeaderFields();

        return new HttpURLConnectionGitHubConnectorResponse(request, statusCode, headers, connection);
    }

    @Nonnull
    private static HttpURLConnection setupConnection(@Nonnull HttpConnector connector,
            @Nonnull GitHubConnectorRequest request) throws IOException {
        HttpURLConnection connection = connector.connect(request.url());
        setRequestMethod(request.method(), connection);
        buildRequest(request, connection);

        return connection;
    }

    /**
     * Set up the request parameters or POST payload.
     */
    private static void buildRequest(GitHubConnectorRequest request, HttpURLConnection connection) throws IOException {
        for (Map.Entry<String, List<String>> e : request.allHeaders().entrySet()) {
            List<String> v = e.getValue();
            if (v != null)
                connection.setRequestProperty(e.getKey(), String.join(", ", v));
        }

        if (request.hasBody()) {
            connection.setDoOutput(true);
            IOUtils.copyLarge(request.body(), connection.getOutputStream());
        }
    }

    private static void setRequestMethod(String method, HttpURLConnection connection) throws IOException {
        try {
            connection.setRequestMethod(method);
        } catch (ProtocolException e) {
            // JDK only allows one of the fixed set of verbs. Try to override that
            try {
                Field $method = HttpURLConnection.class.getDeclaredField("method");
                $method.setAccessible(true);
                $method.set(connection, method);
            } catch (Exception x) {
                throw (IOException) new IOException("Failed to set the custom verb").initCause(x);
            }
            // sun.net.www.protocol.https.DelegatingHttpsURLConnection delegates to another HttpURLConnection
            try {
                Field $delegate = connection.getClass().getDeclaredField("delegate");
                $delegate.setAccessible(true);
                Object delegate = $delegate.get(connection);
                if (delegate instanceof HttpURLConnection) {
                    HttpURLConnection nested = (HttpURLConnection) delegate;
                    setRequestMethod(method, nested);
                }
            } catch (NoSuchFieldException x) {
                // no problem
            } catch (IllegalAccessException x) {
                throw (IOException) new IOException("Failed to set the custom verb").initCause(x);
            }
        }
        if (!connection.getRequestMethod().equals(method))
            throw new IllegalStateException("Failed to set the request method to " + method);
    }

    /**
     * Initial response information supplied to a {@link org.kohsuke.github.function.BodyHandler} when a response is
     * initially received and before the body is processed.
     *
     * Implementation specific to {@link HttpURLConnection}. For internal use only.
     */
    public final static class HttpURLConnectionGitHubConnectorResponse extends GitHubConnectorResponse {

        private boolean inputStreamRead = false;
        private byte[] inputBytes = null;
        private boolean errorStreamRead = false;
        private String errorString = null;

        @Nonnull
        private final HttpURLConnection connection;

        HttpURLConnectionGitHubConnectorResponse(@Nonnull GitHubConnectorRequest request,
                int statusCode,
                @Nonnull Map<String, List<String>> headers,
                @Nonnull HttpURLConnection connection) {
            super(request, statusCode, headers);
            this.connection = connection;
        }

        /**
         * {@inheritDoc}
         */
        public InputStream bodyStream() throws IOException {
            if (connection.getResponseCode() >= HTTP_BAD_REQUEST) {
                if (connection.getResponseCode() == HTTP_NOT_FOUND) {
                    throw new FileNotFoundException(request().url().toString());
                } else {
                    throw new HttpException(errorMessage(),
                            connection.getResponseCode(),
                            connection.getResponseMessage(),
                            connection.getURL().toString());
                }
            }

            synchronized (this) {
                if (!inputStreamRead) {
                    try (InputStream stream = wrapStream(connection.getInputStream())) {
                        if (stream != null) {
                            inputBytes = IOUtils.toByteArray(stream);
                        }
                    }
                    inputStreamRead = true;
                }
            }

            return inputBytes == null ? null : new ByteArrayInputStream(inputBytes);
        }

        /**
         * {@inheritDoc}
         */
        public String errorMessage() {
            String result = null;
            try {
                synchronized (this) {
                    if (!errorStreamRead) {
                        try (InputStream stream = wrapStream(connection.getErrorStream())) {
                            if (stream != null) {
                                errorString = new String(IOUtils.toByteArray(stream), StandardCharsets.UTF_8);
                            }
                        }
                        errorStreamRead = true;
                    }
                }
                if (errorString != null) {
                    result = errorString;
                }
            } catch (Exception e) {
                LOGGER.log(FINER, "Ignored exception get error message", e);
            }
            return result;
        }

        @SuppressFBWarnings(value = { "EI_EXPOSE_REP" },
                justification = "Internal implementation class. Should not be used externally.")
        @Nonnull
        @Override
        @Deprecated
        public HttpURLConnection toHttpURLConnection() {
            return connection;
        }

        /**
         * Handles the "Content-Encoding" header.
         *
         * @param stream
         *            the stream to possibly wrap
         *
         */
        private InputStream wrapStream(InputStream stream) throws IOException {
            String encoding = header("Content-Encoding");
            if (encoding == null || stream == null)
                return stream;
            if (encoding.equals("gzip"))
                return new GZIPInputStream(stream);

            throw new UnsupportedOperationException("Unexpected Content-Encoding: " + encoding);
        }

        private static final Logger LOGGER = Logger.getLogger(GitHub.class.getName());

        @Override
        public void close() throws IOException {
            IOUtils.closeQuietly(connection.getInputStream());
        }
    }

}
