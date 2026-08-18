/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import org.junit.Test;

import com.google.gson.JsonObject;

public class ProviderCancellationTest {

    @Test
    public void cancellingMappedResponseCancelsRootHttpRequest() {
        TrackingHttpClient http = new TrackingHttpClient();
        char[] secret = "root-secret".toCharArray(); //$NON-NLS-1$
        ProviderConfiguration configuration = ProviderConfiguration.builder()
                .id("test") //$NON-NLS-1$
                .displayName("Test") //$NON-NLS-1$
                .baseUri(URI.create("https://provider.example/v1")) //$NON-NLS-1$
                .defaultModel("model") //$NON-NLS-1$
                .apiKey(secret)
                .build();
        JsonObject body = new JsonObject();
        body.addProperty("model", "model"); //$NON-NLS-1$ //$NON-NLS-2$

        CompletableFuture<ChatCompletionResponse> response =
                new RuntimeProviderFactory(http).create(configuration).completeRaw(body);
        assertEquals("/v1/chat/completions", http.request.uri().getPath()); //$NON-NLS-1$
        assertEquals("Bearer root-secret", http.request.headers() //$NON-NLS-1$
                .firstValue("Authorization").orElseThrow()); //$NON-NLS-1$

        assertTrue(response.cancel(true));
        assertTrue(http.root.isCancelled());
        java.util.Arrays.fill(secret, '\0');
    }

    @Test
    public void cancellingStreamBeforeHeadersCancelsRootHttpRequest() {
        StreamingHttpClient http = new StreamingHttpClient();
        OpenAiCompatibleProvider provider = new RuntimeProviderFactory(http).create(configuration());

        CompletableFuture<Void> response = provider.stream(new JsonObject(), event -> { });

        assertTrue(response.cancel(true));
        assertTrue(http.root.isCancelled());
        assertEquals("text/event-stream", http.request.headers() //$NON-NLS-1$
                .firstValue("Accept").orElseThrow()); //$NON-NLS-1$
    }

    @Test
    public void cancellingStreamAfterHeadersClosesResponseInputStream() throws Exception {
        StreamingHttpClient http = new StreamingHttpClient();
        BlockingInputStream body = new BlockingInputStream();
        OpenAiCompatibleProvider provider = new RuntimeProviderFactory(http).create(configuration());
        CompletableFuture<Void> response = provider.stream(new JsonObject(), event -> { });
        http.root.complete(new InputStreamResponse(http.request, body));
        assertTrue(body.readStarted.await(1, TimeUnit.SECONDS));

        assertTrue(response.cancel(true));

        assertTrue(body.closed.await(1, TimeUnit.SECONDS));
        assertTrue(response.isCancelled());
    }

    private static ProviderConfiguration configuration() {
        return ProviderConfiguration.builder()
                .id("test") //$NON-NLS-1$
                .displayName("Test") //$NON-NLS-1$
                .baseUri(URI.create("https://provider.example/v1")) //$NON-NLS-1$
                .defaultModel("model") //$NON-NLS-1$
                .build();
    }

    private static final class TrackingHttpClient extends HttpClient {
        private final CompletableFuture<HttpResponse<String>> root = new CompletableFuture<>();
        private HttpRequest request;

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            this.request = request;
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) root;
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

    private static final class StreamingHttpClient extends HttpClient {
        private final CompletableFuture<HttpResponse<InputStream>> root = new CompletableFuture<>();
        private HttpRequest request;

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            this.request = request;
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) root;
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

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                if (!closed.await(3, TimeUnit.SECONDS)) throw new IOException("test read timed out"); //$NON-NLS-1$
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("test read interrupted"); //$NON-NLS-1$
            }
            throw new IOException("test stream closed"); //$NON-NLS-1$
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class InputStreamResponse implements HttpResponse<InputStream> {
        private final HttpRequest request;
        private final InputStream body;

        private InputStreamResponse(HttpRequest request, InputStream body) {
            this.request = request;
            this.body = body;
        }

        @Override public int statusCode() { return 200; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (left, right) -> true); }
        @Override public InputStream body() { return body; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
