/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal typed request for a non-streaming OpenAI-compatible completion.
 *
 * <p>Tool serialization and streaming deliberately remain outside this first
 * transport slice. They need a reviewed, provider-neutral model contract
 * rather than a copy of core's current types.</p>
 */
public final class ChatCompletionRequest {

    private final List<ChatMessage> messages;
    private final String model;
    private final Integer maxTokens;
    private final Double temperature;

    private ChatCompletionRequest(Builder builder) {
        if (builder.messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty"); //$NON-NLS-1$
        }
        this.messages = List.copyOf(builder.messages);
        this.model = optionalText(builder.model, "model"); //$NON-NLS-1$
        this.maxTokens = positive(builder.maxTokens, "maxTokens"); //$NON-NLS-1$
        this.temperature = validTemperature(builder.temperature);
    }

    /** @return a new request builder */
    public static Builder builder() {
        return new Builder();
    }

    /** @return immutable request messages */
    public List<ChatMessage> messages() {
        return messages;
    }

    /** @return optional model overriding the configured default */
    public Optional<String> model() {
        return Optional.ofNullable(model);
    }

    /** @return optional response token limit */
    public Optional<Integer> maxTokens() {
        return Optional.ofNullable(maxTokens);
    }

    /** @return optional sampling temperature */
    public Optional<Double> temperature() {
        return Optional.ofNullable(temperature);
    }

    /** Builder for a typed chat-completions request. */
    public static final class Builder {
        private final List<ChatMessage> messages = new ArrayList<>();
        private String model;
        private Integer maxTokens;
        private Double temperature;

        private Builder() {
        }

        public Builder addMessage(ChatMessage message) {
            messages.add(Objects.requireNonNull(message, "message")); //$NON-NLS-1$
            return this;
        }

        public Builder messages(List<ChatMessage> messages) {
            this.messages.clear();
            this.messages.addAll(Objects.requireNonNull(messages, "messages")); //$NON-NLS-1$
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public ChatCompletionRequest build() {
            return new ChatCompletionRequest(this);
        }
    }

    private static String optionalText(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank when supplied"); //$NON-NLS-1$
        }
        return value;
    }

    private static Integer positive(Integer value, String field) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + " must be positive when supplied"); //$NON-NLS-1$
        }
        return value;
    }

    private static Double validTemperature(Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0.0 || value > 2.0)) {
            throw new IllegalArgumentException("temperature must be finite and between 0 and 2"); //$NON-NLS-1$
        }
        return value;
    }
}
