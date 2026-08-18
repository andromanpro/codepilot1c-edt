package com.codepilot1c.runtime.mcp;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import org.junit.Test;

import com.google.gson.JsonObject;

public class McpClientCancellationTest {

    @Test
    public void cancellingToolCallCancelsRootHttpRequest() {
        TrackingHttpClient http = new TrackingHttpClient();
        McpClientConfig config = McpClientConfig.builder("http://localhost:8123/mcp").build(); //$NON-NLS-1$
        McpClient client = new McpClient(config, http);
        client.initialize().join();

        CompletableFuture<ToolCallResult> call = client.callTool("slow", new JsonObject()); //$NON-NLS-1$
        assertTrue(call.cancel(true));
        assertTrue(http.toolRoot.isCancelled());

        client.close();
    }

    private static final class TrackingHttpClient extends HttpClient {
        private final AtomicInteger requests = new AtomicInteger();
        private final CompletableFuture<HttpResponse<String>> toolRoot = new CompletableFuture<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            int index = requests.getAndIncrement();
            CompletableFuture<HttpResponse<String>> response;
            if (index == 0) {
                response = CompletableFuture.completedFuture(new Response(request, 200,
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\",\"serverInfo\":{},\"capabilities\":{}}}", //$NON-NLS-1$
                        Map.of("Mcp-Session-Id", List.of("session")))); //$NON-NLS-1$ //$NON-NLS-2$
            } else if (index == 1) {
                response = toolRoot;
            } else {
                response = CompletableFuture.completedFuture(new Response(request, 204, "", Map.of())); //$NON-NLS-1$
            }
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException, InterruptedException {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException failure) {
                throw new AssertionError(failure);
            }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    private record Response(HttpRequest request, int statusCode, String body,
            Map<String, List<String>> headerValues) implements HttpResponse<String> {
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(headerValues, (left, right) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
