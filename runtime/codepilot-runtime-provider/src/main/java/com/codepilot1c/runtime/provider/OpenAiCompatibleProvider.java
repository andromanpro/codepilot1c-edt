/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
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

    /**
     * Executes an incremental OpenAI-compatible chat completion.
     *
     * <p>The caller's JSON object is not mutated. The wire copy always sets
     * {@code stream=true} and {@code stream_options.include_usage=true}.
     * Events are delivered in response order. Cancelling the returned future
     * cancels the root HTTP future and closes an already-open response body.</p>
     *
     * @param requestBody complete JSON-object request body
     * @param listener ordered stream-event recipient
     * @return future completed after {@link ProviderStreamEvent.Done}, or
     *         failed with a typed {@link ProviderStreamException}
     */
    public CompletableFuture<Void> stream(JsonObject requestBody, ProviderStreamListener listener) {
        Objects.requireNonNull(requestBody, "requestBody"); //$NON-NLS-1$
        Objects.requireNonNull(listener, "listener"); //$NON-NLS-1$
        JsonObject streamingRequest = requestBody.deepCopy();
        streamingRequest.addProperty("stream", true); //$NON-NLS-1$
        JsonElement configuredOptions = streamingRequest.get("stream_options"); //$NON-NLS-1$
        JsonObject streamOptions = configuredOptions != null && configuredOptions.isJsonObject()
                ? configuredOptions.getAsJsonObject()
                : new JsonObject();
        streamOptions.addProperty("include_usage", true); //$NON-NLS-1$
        streamingRequest.add("stream_options", streamOptions); //$NON-NLS-1$

        StreamOperation operation = new StreamOperation(listener);
        operation.start(streamingRequest.toString());
        return operation.result();
    }

    /**
     * Alias matching {@link #completeRaw(JsonObject)} naming for callers whose
     * adapters distinguish typed and caller-serialized requests.
     *
     * @param requestBody complete JSON-object request body
     * @param listener ordered stream-event recipient
     * @return streaming completion future
     */
    public CompletableFuture<Void> streamRaw(JsonObject requestBody, ProviderStreamListener listener) {
        return stream(requestBody, listener);
    }

    private CompletableFuture<ChatCompletionResponse> send(String requestBody) {
        CompletableFuture<HttpResponse<String>> request = httpClient.sendAsync(
                request(requestBody, false), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private HttpRequest request(String requestBody, boolean streaming) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(configuration.chatCompletionsEndpoint())
                .timeout(configuration.requestTimeout())
                .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (streaming) builder.header("Accept", "text/event-stream"); //$NON-NLS-1$ //$NON-NLS-2$

        for (Map.Entry<String, String> header : configuration.headers().entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        addAuthorization(builder);
        return builder.build();
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

    private final class StreamOperation {
        private final ProviderStreamListener listener;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private final AtomicReference<CompletableFuture<HttpResponse<InputStream>>> root = new AtomicReference<>();
        private final AtomicReference<InputStream> responseBody = new AtomicReference<>();

        private StreamOperation(ProviderStreamListener listener) {
            this.listener = listener;
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) cancelTransport();
            });
        }

        private CompletableFuture<Void> result() {
            return result;
        }

        private void start(String requestBody) {
            CompletableFuture<HttpResponse<InputStream>> requestFuture;
            try {
                requestFuture = httpClient.sendAsync(
                        request(requestBody, true), HttpResponse.BodyHandlers.ofInputStream());
                root.set(requestFuture);
            } catch (RuntimeException failure) {
                fail(transportFailure("Provider stream could not be started")); //$NON-NLS-1$
                return;
            }
            if (result.isCancelled()) {
                requestFuture.cancel(true);
                return;
            }
            requestFuture.whenComplete(this::acceptResponse);
        }

        private void acceptResponse(HttpResponse<InputStream> response, Throwable failure) {
            if (result.isDone()) {
                if (response != null) close(response.body());
                return;
            }
            if (failure != null) {
                fail(transportFailure("Provider stream transport failed")); //$NON-NLS-1$
                return;
            }
            if (response == null || response.body() == null) {
                fail(responseFailure("Provider stream returned no response body")); //$NON-NLS-1$
                return;
            }
            InputStream body = response.body();
            responseBody.set(body);
            if (result.isDone()) {
                close(body);
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                close(body);
                fail(new ProviderStreamException(ProviderStreamException.Kind.RESPONSE,
                        "Provider stream returned an HTTP error", response.statusCode())); //$NON-NLS-1$
                return;
            }
            CompletableFuture.runAsync(() -> read(body));
        }

        private void read(InputStream body) {
            OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(this::emit);
            SseEventParser parser = new SseEventParser(accumulator::accept);
            char[] buffer = new char[2048];
            try (Reader reader = new InputStreamReader(body, StandardCharsets.UTF_8)) {
                while (!result.isDone()) {
                    int count = reader.read(buffer);
                    if (count < 0) break;
                    parser.accept(buffer, 0, count);
                    if (accumulator.isDone()) break;
                }
                if (!result.isDone()) parser.finish();
                if (!result.isDone() && !accumulator.isDone()) {
                    fail(transportFailure("Provider stream ended before its completion marker")); //$NON-NLS-1$
                }
            } catch (ProviderStreamException failure) {
                fail(failure);
            } catch (IOException failure) {
                if (!result.isCancelled()) {
                    fail(transportFailure("Provider stream transport failed while reading")); //$NON-NLS-1$
                }
            } catch (RuntimeException failure) {
                fail(new ProviderStreamException(ProviderStreamException.Kind.LISTENER,
                        "Provider stream listener failed")); //$NON-NLS-1$
            } finally {
                responseBody.compareAndSet(body, null);
                close(body);
            }
        }

        private void emit(ProviderStreamEvent event) {
            listener.onEvent(event);
            if (event instanceof ProviderStreamEvent.Done) result.complete(null);
        }

        private void fail(ProviderStreamException failure) {
            if (result.isDone()) return;
            try {
                listener.onEvent(new ProviderStreamEvent.Error(failure));
            } catch (RuntimeException listenerFailure) {
                // Preserve the original transport/response failure without
                // retaining possibly sensitive listener diagnostics.
            }
            result.completeExceptionally(failure);
            cancelTransport();
        }

        private void cancelTransport() {
            CompletableFuture<HttpResponse<InputStream>> requestFuture = root.get();
            if (requestFuture != null && !requestFuture.isDone()) requestFuture.cancel(true);
            close(responseBody.getAndSet(null));
        }

        private ProviderStreamException transportFailure(String message) {
            return new ProviderStreamException(ProviderStreamException.Kind.TRANSPORT, message);
        }

        private ProviderStreamException responseFailure(String message) {
            return new ProviderStreamException(ProviderStreamException.Kind.RESPONSE, message);
        }
    }

    private static void close(InputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing is best-effort during completion and cancellation.
        }
    }
}
