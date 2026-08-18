/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

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
}
