/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

/**
 * Plain-Java transport for the OpenAI-compatible Chat Completions API.
 *
 * <p>The provider does not perform logging. In particular, it never logs
 * bearer credentials, custom headers, or request content. Network failures
 * are propagated through the returned future.</p>
 */
public final class OpenAiCompatibleProvider {

    private final ProviderConfiguration configuration;
    private final HttpClient httpClient;

    OpenAiCompatibleProvider(ProviderConfiguration configuration, HttpClient httpClient) {
        this.configuration = Objects.requireNonNull(configuration, "configuration"); //$NON-NLS-1$
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient"); //$NON-NLS-1$
        if (configuration.protocol() != ProviderProtocol.OPENAI_COMPATIBLE) {
            throw new IllegalArgumentException("OpenAiCompatibleProvider requires OPENAI_COMPATIBLE protocol"); //$NON-NLS-1$
        }
    }

    /** @return immutable host configuration */
    public ProviderConfiguration configuration() {
        return configuration;
    }

    HttpClient httpClient() {
        return httpClient;
    }

    /**
     * Executes a non-streaming chat completion request.
     *
     * @param request typed request; its model, when supplied, overrides the
     *                configuration default for this request only
     * @return future with raw HTTP response; non-2xx responses remain visible
     *         to the caller as normal response values
     */
    public CompletableFuture<ChatCompletionResponse> complete(ChatCompletionRequest request) {
        Objects.requireNonNull(request, "request"); //$NON-NLS-1$
        return send(serialize(request));
    }

    /**
     * Executes a caller-serialized OpenAI-compatible chat completion request.
     *
     * <p>This narrow transport entry point allows a provider-neutral agent
     * adapter to serialize tool calls without moving an agent model into the
     * transport module. The payload is never logged or included in errors.</p>
     *
     * @param requestBody complete JSON-object request body
     * @return future with the raw HTTP response
     */
    public CompletableFuture<ChatCompletionResponse> completeRaw(JsonObject requestBody) {
        Objects.requireNonNull(requestBody, "requestBody"); //$NON-NLS-1$
        return send(requestBody.deepCopy().toString());
    }

    private CompletableFuture<ChatCompletionResponse> send(String requestBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(configuration.chatCompletionsEndpoint())
                .timeout(configuration.requestTimeout())
                .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));

        for (Map.Entry<String, String> header : configuration.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        addAuthorization(builder);

        CompletableFuture<HttpResponse<String>> request = httpClient.sendAsync(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        CompletableFuture<ChatCompletionResponse> response = new CompletableFuture<>();
        request.whenComplete((value, failure) -> {
            if (failure != null) response.completeExceptionally(failure);
            else response.complete(new ChatCompletionResponse(value.statusCode(), value.body()));
        });
        response.whenComplete((ignored, failure) -> {
            if (response.isCancelled()) request.cancel(true);
        });
        return response;
    }

    private void addAuthorization(HttpRequest.Builder builder) {
        if (!configuration.hasApiKey() || configuration.hasHeader("Authorization")) { //$NON-NLS-1$
            return;
        }
        char[] apiKey = configuration.copyApiKey();
        try {
            builder.header("Authorization", "Bearer " + new String(apiKey)); //$NON-NLS-1$ //$NON-NLS-2$
        } finally {
            java.util.Arrays.fill(apiKey, '\0');
        }
    }

    private String serialize(ChatCompletionRequest request) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        field(json, "model", request.model().orElse(configuration.defaultModel())); //$NON-NLS-1$
        json.append(",\"messages\":["); //$NON-NLS-1$
        for (int index = 0; index < request.messages().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            ChatMessage message = request.messages().get(index);
            json.append('{');
            field(json, "role", message.role()); //$NON-NLS-1$
            json.append(',');
            field(json, "content", message.content()); //$NON-NLS-1$
            json.append('}');
        }
        json.append(']');
        request.maxTokens().ifPresent(value -> json.append(",\"max_tokens\":").append(value)); //$NON-NLS-1$
        request.temperature().ifPresent(value -> json.append(",\"temperature\":").append(value)); //$NON-NLS-1$
        json.append('}');
        return json.toString();
    }

    private static void field(StringBuilder json, String name, String value) {
        quote(json, name);
        json.append(':');
        quote(json, value);
    }

    private static void quote(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"':
                    json.append("\\\""); //$NON-NLS-1$
                    break;
                case '\\':
                    json.append("\\\\"); //$NON-NLS-1$
                    break;
                case '\b':
                    json.append("\\b"); //$NON-NLS-1$
                    break;
                case '\f':
                    json.append("\\f"); //$NON-NLS-1$
                    break;
                case '\n':
                    json.append("\\n"); //$NON-NLS-1$
                    break;
                case '\r':
                    json.append("\\r"); //$NON-NLS-1$
                    break;
                case '\t':
                    json.append("\\t"); //$NON-NLS-1$
                    break;
                default:
                    if (current < 0x20) {
                        json.append(String.format("\\u%04x", (int) current)); //$NON-NLS-1$
                    } else {
                        json.append(current);
                    }
                    break;
            }
        }
        json.append('"');
    }
}
