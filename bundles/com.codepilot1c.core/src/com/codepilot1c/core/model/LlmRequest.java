/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a request to an LLM provider.
 */
public class LlmRequest {

    private final List<LlmMessage> messages;
    private final List<ToolDefinition> tools;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final boolean stream;
    private final ToolChoice toolChoice;

    private LlmRequest(Builder builder) {
        this.messages = Collections.unmodifiableList(new ArrayList<>(builder.messages));
        this.tools = builder.tools.isEmpty() ? null : Collections.unmodifiableList(new ArrayList<>(builder.tools));
        this.model = builder.model;
        this.maxTokens = builder.maxTokens;
        this.temperature = builder.temperature;
        this.stream = builder.stream;
        this.toolChoice = builder.toolChoice;
    }

    public List<LlmMessage> getMessages() {
        return messages;
    }

    public List<ToolDefinition> getTools() {
        return tools;
    }

    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }

    public String getModel() {
        return model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public boolean isStream() {
        return stream;
    }

    public ToolChoice getToolChoice() {
        return toolChoice;
    }

    /**
     * Specifies how the model should choose tools.
     */
    public enum ToolChoice {
        /** Model decides whether to use tools */
        AUTO,
        /** Model must use a tool */
        REQUIRED,
        /** Model should not use tools */
        NONE
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link LlmRequest}.
     */
    public static class Builder {
        private final List<LlmMessage> messages = new ArrayList<>();
        private final List<ToolDefinition> tools = new ArrayList<>();
        private String model;
        private int maxTokens;
        private double temperature = 0.7;
        private boolean stream = false;
        private ToolChoice toolChoice = ToolChoice.AUTO;

        /**
         * Adds a message to the request.
         *
         * @param message the message to add
         * @return this builder
         */
        public Builder addMessage(LlmMessage message) {
            this.messages.add(Objects.requireNonNull(message));
            return this;
        }

        /**
         * Sets all messages for the request (replaces existing).
         *
         * @param messages the messages to set
         * @return this builder
         */
        public Builder messages(List<LlmMessage> messages) {
            this.messages.clear();
            if (messages != null) {
                this.messages.addAll(messages);
            }
            return this;
        }

        /**
         * Adds a system message.
         *
         * @param content the message content
         * @return this builder
         */
        public Builder systemMessage(String content) {
            return addMessage(LlmMessage.system(content));
        }

        /**
         * Adds a user message.
         *
         * @param content the message content
         * @return this builder
         */
        public Builder userMessage(String content) {
            return addMessage(LlmMessage.user(content));
        }

        /**
         * Sets the model to use.
         *
         * @param model the model identifier
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Sets the maximum number of tokens to generate.
         *
         * @param maxTokens the maximum tokens
         * @return this builder
         */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Sets the temperature for response generation.
         *
         * @param temperature the temperature (0.0 to 1.0)
         * @return this builder
         */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Enables or disables streaming.
         *
         * @param stream true to enable streaming
         * @return this builder
         */
        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        /**
         * Adds a tool definition.
         *
         * @param tool the tool to add
         * @return this builder
         */
        public Builder addTool(ToolDefinition tool) {
            this.tools.add(Objects.requireNonNull(tool));
            return this;
        }

        /**
         * Adds multiple tool definitions.
         *
         * @param tools the tools to add
         * @return this builder
         */
        public Builder tools(List<ToolDefinition> tools) {
            this.tools.addAll(tools);
            return this;
        }

        /**
         * Sets the tool choice mode.
         *
         * @param toolChoice how the model should choose tools
         * @return this builder
         */
        public Builder toolChoice(ToolChoice toolChoice) {
            this.toolChoice = toolChoice;
            return this;
        }

        /**
         * Builds the request.
         *
         * @return the built request
         */
        public LlmRequest build() {
            if (messages.isEmpty()) {
                throw new IllegalStateException("At least one message is required"); //$NON-NLS-1$
            }
            return new LlmRequest(this);
        }
    }
}
