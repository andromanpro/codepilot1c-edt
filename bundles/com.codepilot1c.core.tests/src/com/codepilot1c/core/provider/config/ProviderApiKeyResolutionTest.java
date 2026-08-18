package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.codepilot1c.core.provider.config.ModelFetchService.FetchResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class ProviderApiKeyResolutionTest {

    @Test
    public void dynamicProviderUsesInjectedSecureKeyWhenConfigHasNoPlaintext() throws Exception {
        LlmProviderConfig config = configured("dynamic", "https://example.com/v1"); //$NON-NLS-1$ //$NON-NLS-2$
        DynamicLlmProvider provider = new DynamicLlmProvider(
                config, ignored -> "secure-dynamic-key", () -> 60); //$NON-NLS-1$

        assertTrue(provider.isConfigured());
        Method buildHttpRequest = DynamicLlmProvider.class.getDeclaredMethod("buildHttpRequest", String.class); //$NON-NLS-1$
        buildHttpRequest.setAccessible(true);
        HttpRequest request = (HttpRequest) buildHttpRequest.invoke(provider, "{}"); //$NON-NLS-1$

        assertEquals("Bearer secure-dynamic-key", //$NON-NLS-1$
                request.headers().firstValue("Authorization").orElseThrow()); //$NON-NLS-1$
    }

    @Test
    public void modelFetchUsesInjectedSecureKeyForConfigOverload() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", exchange -> respondWithModels(exchange, authorization)); //$NON-NLS-1$
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            ModelFetchService service = new ModelFetchService(client, ignored -> "secure-model-key"); //$NON-NLS-1$
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; //$NON-NLS-1$ //$NON-NLS-2$

            FetchResult result = service.fetchModels(configured("models", baseUrl)) //$NON-NLS-1$
                    .get(10, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertEquals("Bearer secure-model-key", authorization.get()); //$NON-NLS-1$
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void ollamaNeverReceivesStaleStaticBearerFromResolver() throws Exception {
        LlmProviderConfig config = configured("ollama", "http://127.0.0.1:11434"); //$NON-NLS-1$ //$NON-NLS-2$
        config.setType(ProviderType.OLLAMA);
        DynamicLlmProvider provider = new DynamicLlmProvider(
                config, ignored -> "old-secure-key", () -> 60); //$NON-NLS-1$

        Method buildHttpRequest = DynamicLlmProvider.class.getDeclaredMethod("buildHttpRequest", String.class); //$NON-NLS-1$
        buildHttpRequest.setAccessible(true);
        HttpRequest request = (HttpRequest) buildHttpRequest.invoke(provider, "{}"); //$NON-NLS-1$

        assertFalse(request.headers().firstValue("Authorization").isPresent()); //$NON-NLS-1$
    }

    @Test
    public void ollamaModelFetchNeverReceivesStaleStaticBearerFromResolver() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/tags", exchange -> respondWithOllamaModels(exchange, authorization)); //$NON-NLS-1$
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            ModelFetchService service = new ModelFetchService(client, ignored -> "old-secure-key"); //$NON-NLS-1$
            LlmProviderConfig config = configured(
                    "ollama-models", "http://127.0.0.1:" + server.getAddress().getPort()); //$NON-NLS-1$ //$NON-NLS-2$
            config.setType(ProviderType.OLLAMA);

            FetchResult result = service.fetchModels(config).get(10, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertNull(authorization.get());
        } finally {
            server.stop(0);
        }
    }

    private static LlmProviderConfig configured(String id, String baseUrl) {
        return new LlmProviderConfig(id, id, ProviderType.OPENAI_COMPATIBLE,
                baseUrl, null, "model", 4096); //$NON-NLS-1$
    }

    private static void respondWithModels(HttpExchange exchange, AtomicReference<String> authorization)
            throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization")); //$NON-NLS-1$
        byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void respondWithOllamaModels(HttpExchange exchange, AtomicReference<String> authorization)
            throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization")); //$NON-NLS-1$
        byte[] body = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
