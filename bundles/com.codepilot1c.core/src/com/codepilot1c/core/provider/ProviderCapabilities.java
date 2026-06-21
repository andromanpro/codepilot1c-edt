/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

/**
 * Declares provider-specific runtime capabilities.
 */
public final class ProviderCapabilities {

    private static final ProviderCapabilities NONE = builder().build();

    private final boolean codePilotBackend;
    private final boolean backendOptimizations;
    private final boolean promptCacheHeaders;
    private final boolean resolvedModel;
    private final boolean textToolCallFallback;
    private final boolean nativeDeferredToolLoading;
    private final boolean imageInput;
    private final boolean documentInput;
    private final boolean attachmentMetadata;
    private final long maxAttachmentBytes;
    private final int maxAttachmentsPerMessage;
    private final boolean streamUsage;

    private ProviderCapabilities(Builder builder) {
        this.codePilotBackend = builder.codePilotBackend;
        this.backendOptimizations = builder.backendOptimizations;
        this.promptCacheHeaders = builder.promptCacheHeaders;
        this.resolvedModel = builder.resolvedModel;
        this.textToolCallFallback = builder.textToolCallFallback;
        this.nativeDeferredToolLoading = builder.nativeDeferredToolLoading;
        this.imageInput = builder.imageInput;
        this.documentInput = builder.documentInput;
        this.attachmentMetadata = builder.attachmentMetadata;
        this.maxAttachmentBytes = builder.maxAttachmentBytes;
        this.maxAttachmentsPerMessage = builder.maxAttachmentsPerMessage;
        this.streamUsage = builder.streamUsage;
    }

    public static ProviderCapabilities none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isCodePilotBackend() {
        return codePilotBackend;
    }

    /**
     * Returns {@code true} when content-based tool call fallback should be enabled.
     *
     * <p>This covers providers that may emit tool calls as text content instead of structured API responses.
     * Structured tool calls remain the primary path; text parsing is only a safety net.</p>
     */
    public boolean supportsTextToolCallFallback() {
        return textToolCallFallback;
    }

    public boolean supportsBackendOptimizations() {
        return backendOptimizations;
    }

    public boolean supportsPromptCacheHeaders() {
        return promptCacheHeaders;
    }

    public boolean supportsResolvedModel() {
        return resolvedModel;
    }

    /**
     * Returns {@code true} if the provider supports native deferred tool loading
     * (e.g., Anthropic's tool_choice with deferred loading). When {@code false},
     * the agent runner uses {@code discover_tools} meta-tool to reduce the
     * initial tool surface for OpenAI-compatible providers.
     */
    public boolean supportsNativeDeferredToolLoading() {
        return nativeDeferredToolLoading;
    }

    /**
     * Returns {@code true} if deferred tool loading via {@code discover_tools}
     * should be activated. This is the case when the provider does NOT support
     * native deferred loading AND is using a CodePilot backend.
     */
    public boolean shouldUseDeferredLoading() {
        return codePilotBackend && !nativeDeferredToolLoading;
    }

    public boolean supportsImageInput() {
        return imageInput;
    }

    public boolean supportsDocumentInput() {
        return documentInput;
    }

    public boolean supportsAttachmentMetadata() {
        return attachmentMetadata;
    }

    public long getMaxAttachmentBytes() {
        return maxAttachmentBytes;
    }

    public int getMaxAttachmentsPerMessage() {
        return maxAttachmentsPerMessage;
    }

    /**
     * Returns {@code true} when the provider supports the OpenAI-compatible
     * {@code stream_options: {include_usage: true}} request field and emits
     * a terminal {@code usage} JSON object on streamed responses.
     *
     * <p>Gates real token-usage reporting for the streaming path. When
     * {@code false}, callers fall back to local estimation.</p>
     *
     * <p>Defaults:</p>
     * <ul>
     *   <li>{@code true} for CodePilot backend (confirmed spec)</li>
     *   <li>{@code false} for all other providers (generic OpenAI-compatible
     *       gateways may silently ignore the field and never emit usage)</li>
     * </ul>
     */
    public boolean supportsStreamUsage() {
        return streamUsage;
    }

    /**
     * Best-effort heuristic for multimodal image input support when the provider
     * exposes an OpenAI-compatible API but does not publish modality metadata.
     *
     * <p>This is intentionally conservative enough to avoid enabling images for
     * clearly text-only models, while still recognizing the common multimodal
     * families used behind generic OpenAI-compatible gateways.</p>
     *
     * @param model the configured model name
     * @return {@code true} when the model name strongly suggests vision support
     */
    public static boolean inferImageInputFromModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("vision") || lower.contains("vl") || lower.contains("image")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return true;
        }
        // OpenAI multimodal families: GPT-4o/4.1, GPT-5.x (incl. gpt-5.5), and the o1/o3/o4 reasoning models.
        if (lower.startsWith("gpt-4o") || lower.startsWith("gpt-4.1") || lower.startsWith("gpt-5") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || lower.startsWith("o4") || lower.startsWith("o3") || lower.startsWith("o1")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return true;
        }
        // Anthropic Claude (3.x/4.x and Fable) are multimodal.
        if (lower.startsWith("claude") || lower.contains("sonnet") || lower.contains("opus") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || lower.contains("haiku") || lower.contains("fable")) { //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        if (lower.startsWith("gemini")) { //$NON-NLS-1$
            return true;
        }
        if (lower.startsWith("pixtral") || lower.startsWith("llava")) { //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        if (lower.startsWith("qvq")) { //$NON-NLS-1$
            return true;
        }
        return false;
    }

    public static final class Builder {
        private boolean codePilotBackend;
        private boolean backendOptimizations;
        private boolean promptCacheHeaders;
        private boolean resolvedModel;
        private boolean textToolCallFallback;
        private boolean nativeDeferredToolLoading;
        private boolean imageInput;
        private boolean documentInput;
        private boolean attachmentMetadata;
        private long maxAttachmentBytes = 10L * 1024L * 1024L;
        private int maxAttachmentsPerMessage = 5;
        private boolean streamUsage;

        public Builder codePilotBackend(boolean codePilotBackend) {
            this.codePilotBackend = codePilotBackend;
            return this;
        }

        public Builder backendOptimizations(boolean backendOptimizations) {
            this.backendOptimizations = backendOptimizations;
            return this;
        }

        public Builder promptCacheHeaders(boolean promptCacheHeaders) {
            this.promptCacheHeaders = promptCacheHeaders;
            return this;
        }

        public Builder resolvedModel(boolean resolvedModel) {
            this.resolvedModel = resolvedModel;
            return this;
        }

        public Builder textToolCallFallback(boolean textToolCallFallback) {
            this.textToolCallFallback = textToolCallFallback;
            return this;
        }

        public Builder nativeDeferredToolLoading(boolean nativeDeferredToolLoading) {
            this.nativeDeferredToolLoading = nativeDeferredToolLoading;
            return this;
        }

        public Builder imageInput(boolean imageInput) {
            this.imageInput = imageInput;
            return this;
        }

        public Builder documentInput(boolean documentInput) {
            this.documentInput = documentInput;
            return this;
        }

        public Builder attachmentMetadata(boolean attachmentMetadata) {
            this.attachmentMetadata = attachmentMetadata;
            return this;
        }

        public Builder maxAttachmentBytes(long maxAttachmentBytes) {
            this.maxAttachmentBytes = maxAttachmentBytes;
            return this;
        }

        public Builder maxAttachmentsPerMessage(int maxAttachmentsPerMessage) {
            this.maxAttachmentsPerMessage = maxAttachmentsPerMessage;
            return this;
        }

        public Builder streamUsage(boolean streamUsage) {
            this.streamUsage = streamUsage;
            return this;
        }

        public ProviderCapabilities build() {
            return new ProviderCapabilities(this);
        }
    }
}
