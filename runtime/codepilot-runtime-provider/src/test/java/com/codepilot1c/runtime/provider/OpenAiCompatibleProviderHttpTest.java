/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Integration-level HTTP contract tests for the standalone provider path. */
public class OpenAiCompatibleProviderHttpTest {

    @Test
    public void executesOpenAiCompatibleChatCompletionWithoutLeakingApiKey() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> clientHeader = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization")); //$NON-NLS-1$
            clientHeader.set(exchange.getRequestHeaders().getFirst("X-Client")); //$NON-NLS-1$
            respond(exchange, 200, "{\"id\":\"completion-1\"}"); //$NON-NLS-1$
        });
        char[] suppliedSecret = "test-secret".toCharArray(); //$NON-NLS-1$
        try {
            ProviderConfiguration configuration = configuration(server, suppliedSecret, Map.of("X-Client", "cli-smoke")); //$NON-NLS-1$ //$NON-NLS-2$
            Arrays.fill(suppliedSecret, 'x');
            OpenAiCompatibleProvider provider = new RuntimeProviderFactory().create(configuration);

            ChatCompletionResponse response = provider.complete(ChatCompletionRequest.builder()
                    .addMessage(new ChatMessage("system", "You are concise.")) //$NON-NLS-1$ //$NON-NLS-2$
                    .addMessage(new ChatMessage("user", "Say \"hello\"\nnow")) //$NON-NLS-1$ //$NON-NLS-2$
                    .maxTokens(64)
                    .temperature(0.2)
                    .build()).get();

            assertEquals(200, response.statusCode());
            assertTrue(response.isSuccessful());
            assertEquals("{\"id\":\"completion-1\"}", response.body()); //$NON-NLS-1$
            assertEquals("Bearer test-secret", authorization.get()); //$NON-NLS-1$
            assertEquals("cli-smoke", clientHeader.get()); //$NON-NLS-1$
            assertEquals("{\"model\":\"test-model\",\"messages\":[{\"role\":\"system\",\"content\":\"You are concise.\"},{\"role\":\"user\",\"content\":\"Say \\\"hello\\\"\\nnow\"}],\"max_tokens\":64,\"temperature\":0.2}", //$NON-NLS-1$
                    receivedBody.get());
            assertFalse(configuration.toString().contains("test-secret")); //$NON-NLS-1$
            assertTrue(configuration.hasApiKey());
        } finally {
            Arrays.fill(suppliedSecret, '\0');
            server.stop(0);
        }
    }

    @Test
    public void copiesSecretAndAllowsLocalServerWithoutBearerHeader() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization")); //$NON-NLS-1$
            respond(exchange, 401, "{\"error\":\"missing test auth\"}"); //$NON-NLS-1$
        });
        try {
            ProviderConfiguration configuration = configuration(server, null, Map.of());
            ChatCompletionResponse response = new RuntimeProviderFactory().create(configuration)
                    .complete(ChatCompletionRequest.builder()
                            .addMessage(new ChatMessage("user", "ping")) //$NON-NLS-1$ //$NON-NLS-2$
                            .build())
                    .get();

            assertEquals(401, response.statusCode());
            assertFalse(response.isSuccessful());
            assertEquals(null, authorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void customAuthorizationHeaderOverridesApiKeyBearerHeader() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization")); //$NON-NLS-1$
            respond(exchange, 200, "{}"); //$NON-NLS-1$
        });
        char[] suppliedSecret = "unused-secret".toCharArray(); //$NON-NLS-1$
        try {
            ProviderConfiguration configuration = configuration(server, suppliedSecret,
                    Map.of("authorization", "Token host-owned")); //$NON-NLS-1$ //$NON-NLS-2$
            new RuntimeProviderFactory().create(configuration)
                    .complete(ChatCompletionRequest.builder()
                            .addMessage(new ChatMessage("user", "ping")) //$NON-NLS-1$ //$NON-NLS-2$
                            .build())
                    .get();

            assertEquals("Token host-owned", authorization.get()); //$NON-NLS-1$
        } finally {
            Arrays.fill(suppliedSecret, '\0');
            server.stop(0);
        }
    }

    @Test
    public void configurationOwnsApiKeyCopy() {
        char[] suppliedSecret = "test-secret".toCharArray(); //$NON-NLS-1$
        ProviderConfiguration configuration = ProviderConfiguration.builder()
                .id("test") //$NON-NLS-1$
                .displayName("Test") //$NON-NLS-1$
                .baseUri(URI.create("http://localhost:8080/v1")) //$NON-NLS-1$
                .defaultModel("test-model") //$NON-NLS-1$
                .apiKey(suppliedSecret)
                .build();
        Arrays.fill(suppliedSecret, 'x');

        assertArrayEquals("test-secret".toCharArray(), configuration.copyApiKey()); //$NON-NLS-1$
        assertFalse(configuration.toString().contains("test-secret")); //$NON-NLS-1$
    }

    private static ProviderConfiguration configuration(HttpServer server, char[] apiKey,
            Map<String, String> headers) {
        return ProviderConfiguration.builder()
                .id("smoke-provider") //$NON-NLS-1$
                .displayName("Smoke provider") //$NON-NLS-1$
                .baseUri(URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/")) //$NON-NLS-1$ //$NON-NLS-2$
                .defaultModel("test-model") //$NON-NLS-1$
                .headers(headers)
                .apiKey(apiKey)
                .build();
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0); //$NON-NLS-1$
        server.createContext("/v1/chat/completions", exchange -> handler.handle(exchange)); //$NON-NLS-1$
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
