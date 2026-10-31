package com.codepilot1c.runtime.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.lang.reflect.Field;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    public void cancellingInitializeCancelsRootAndIgnoresLateSuccess() {
        PendingInitializeHttpClient http = new PendingInitializeHttpClient();
        McpClient client = new McpClient(
                McpClientConfig.builder("http://localhost:8123/mcp").build(), http); //$NON-NLS-1$

        CompletableFuture<InitializeResult> initialize = client.initialize();
        assertTrue(initialize.cancel(true));
        assertTrue(http.root.cancelCalled);
        http.completeLateSuccess();

        assertTrue(initialize.isCancelled());
        assertFalse(client.isInitialized());
        assertNull(client.negotiatedProtocol());
        client.close();
    }

    @Test
    public void closeDuringInitializeCancelsRootAndLateSuccessCannotInstallSession() {
        PendingInitializeHttpClient http = new PendingInitializeHttpClient();
        McpClient client = new McpClient(
                McpClientConfig.builder("http://localhost:8123/mcp").build(), http); //$NON-NLS-1$

        CompletableFuture<InitializeResult> initialize = client.initialize();
        client.closeAsync().join();
        assertTrue(http.root.cancelCalled);
        http.completeLateSuccess();

        assertTrue(initialize.isCancelled());
        assertFalse(client.isInitialized());
        assertNull(client.negotiatedProtocol());
    }

    @Test
    public void initializeFutureRejectsExternalMutationAndPreservesOperationIdentity() {
        SequencedInitializeHttpClient http = new SequencedInitializeHttpClient();
        McpClient client = new McpClient(
                McpClientConfig.builder("http://localhost:8123/mcp").build(), http); //$NON-NLS-1$

        CompletableFuture<InitializeResult> first = client.initialize();
        CompletableFuture<String> dependent = first.thenApply(InitializeResult::protocolVersion);
        InitializeResult fake = new InitializeResult("fake", new JsonObject(), //$NON-NLS-1$
                new JsonObject(), null, new JsonObject());
        AtomicInteger supplierCalls = new AtomicInteger();

        assertFalse(first.complete(fake));
        assertFalse(first.completeExceptionally(new IllegalStateException("fake"))); //$NON-NLS-1$
        assertExternalMutationRejected(() -> first.obtrudeValue(fake));
        assertExternalMutationRejected(() -> first.obtrudeException(
                new IllegalStateException("fake"))); //$NON-NLS-1$
        assertExternalMutationRejected(() -> first.completeAsync(() -> {
            supplierCalls.incrementAndGet();
            return fake;
        }));
        assertExternalMutationRejected(() -> first.completeAsync(() -> {
            supplierCalls.incrementAndGet();
            return fake;
        }, Runnable::run));
        assertExternalMutationRejected(() -> first.orTimeout(1, TimeUnit.MILLISECONDS));
        assertExternalMutationRejected(() -> first.completeOnTimeout(
                fake, 1, TimeUnit.MILLISECONDS));

        assertEquals(0, supplierCalls.get());
        assertFalse(first.isDone());
        assertFalse(dependent.isDone());
        assertSame(first, client.initialize());
        assertTrue(first.cancel(true));
        assertTrue(http.root(0).cancelCalled);

        CompletableFuture<InitializeResult> second = client.initialize();
        assertNotSame(first, second);
        assertSame(second, client.initialize());
        http.completeSuccess(0, "stale-session"); //$NON-NLS-1$
        assertFalse(client.isInitialized());
        assertFalse(second.isDone());

        http.completeSuccess(1, "second-session"); //$NON-NLS-1$
        assertEquals("2025-11-25", second.join().protocolVersion()); //$NON-NLS-1$
        assertTrue(client.isInitialized());
        assertEquals("2025-11-25", client.negotiatedProtocol()); //$NON-NLS-1$
        client.close();
    }

    @Test(timeout = 2000)
    public void requestCancellationAndSynchronousCallbacksRunOutsideStateLock() throws Exception {
        CloseDuringSendHttpClient http = new CloseDuringSendHttpClient();
        McpClient client = new McpClient(
                McpClientConfig.builder("http://localhost:8123/mcp").build(), http); //$NON-NLS-1$
        http.bind(client, stateLock(client));

        CompletableFuture<InitializeResult> initialize = client.initialize();

        assertTrue(initialize.isCancelled());
        assertTrue(http.root.cancelCalled);
        assertFalse(http.root.cancelUnderStateLock);
        assertTrue(http.callbackCalled);
        assertFalse(http.callbackUnderStateLock);
        assertFalse(client.isInitialized());
    }

    private static void assertExternalMutationRejected(Runnable mutation) {
        try {
            mutation.run();
            fail("Expected client-owned initialize future to reject external mutation"); //$NON-NLS-1$
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("owned by McpClient")); //$NON-NLS-1$
        }
    }

    private static Object stateLock(McpClient client) throws ReflectiveOperationException {
        Field field = McpClient.class.getDeclaredField("stateLock"); //$NON-NLS-1$
        field.setAccessible(true);
        return field.get(client);
    }

    private abstract static class BaseHttpClient extends HttpClient {
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

    private static final class TrackingHttpClient extends BaseHttpClient {
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

    }

    private static final class PendingInitializeHttpClient extends BaseHttpClient {
        private final NonCancellingFuture<HttpResponse<String>> root = new NonCancellingFuture<>();
        private HttpRequest request;

        void completeLateSuccess() {
            root.complete(new Response(request, 200,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\",\"serverInfo\":{},\"capabilities\":{}}}", //$NON-NLS-1$
                    Map.of("Mcp-Session-Id", List.of("late-session")))); //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            this.request = request;
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) root;
        }

    }

    private static final class SequencedInitializeHttpClient extends BaseHttpClient {
        private final List<NonCancellingFuture<HttpResponse<String>>> roots = new java.util.ArrayList<>();
        private final List<HttpRequest> requests = new java.util.ArrayList<>();

        NonCancellingFuture<HttpResponse<String>> root(int index) {
            return roots.get(index);
        }

        void completeSuccess(int index, String session) {
            roots.get(index).complete(new Response(requests.get(index), 200,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-11-25\",\"serverInfo\":{},\"capabilities\":{}}}", //$NON-NLS-1$
                    Map.of("Mcp-Session-Id", List.of(session)))); //$NON-NLS-1$
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            if ("DELETE".equals(request.method())) { //$NON-NLS-1$
                return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>)
                        CompletableFuture.completedFuture(new Response(request, 204, "", Map.of())); //$NON-NLS-1$
            }
            NonCancellingFuture<HttpResponse<String>> root = new NonCancellingFuture<>();
            requests.add(request);
            roots.add(root);
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) root;
        }
    }

    private static final class CloseDuringSendHttpClient extends BaseHttpClient {
        private final LockCheckingFuture<HttpResponse<String>> root = new LockCheckingFuture<>();
        private final AtomicReference<McpClient> client = new AtomicReference<>();
        private volatile Object stateLock;
        private volatile boolean callbackCalled;
        private volatile boolean callbackUnderStateLock;

        void bind(McpClient value, Object lock) {
            client.set(value);
            stateLock = lock;
            root.stateLock = lock;
            root.whenComplete((ignored, failure) -> {
                callbackCalled = true;
                callbackUnderStateLock = Thread.holdsLock(stateLock);
            });
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            client.get().closeAsync().join();
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) root;
        }
    }

    private static final class NonCancellingFuture<T> extends CompletableFuture<T> {
        private volatile boolean cancelCalled;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalled = true;
            return false;
        }
    }

    private static final class LockCheckingFuture<T> extends CompletableFuture<T> {
        private volatile Object stateLock;
        private volatile boolean cancelCalled;
        private volatile boolean cancelUnderStateLock;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalled = true;
            cancelUnderStateLock = Thread.holdsLock(stateLock);
            return super.cancel(mayInterruptIfRunning);
        }
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
