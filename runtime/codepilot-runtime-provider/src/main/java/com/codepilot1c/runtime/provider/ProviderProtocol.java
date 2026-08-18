/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.provider;

import com.codepilot1c.runtime.spi.ProviderFactoryRegistry.ProviderTypeId;

/**
 * Wire protocols currently available to a standalone runtime host.
 *
 * <p>This enumeration names a protocol rather than a vendor or model. A host
 * may point the OpenAI-compatible protocol at a local server, a hosted
 * service, or a proxy without changing its tool surface.</p>
 */
public enum ProviderProtocol {

    /** OpenAI-compatible Chat Completions HTTP API. */
    OPENAI_COMPATIBLE("openai-compatible"); //$NON-NLS-1$

    private final ProviderTypeId typeId;

    ProviderProtocol(String typeId) {
        this.typeId = new ProviderTypeId(typeId);
    }

    /**
     * Returns the platform-neutral factory registry identifier.
     *
     * @return stable provider protocol identifier
     */
    public ProviderTypeId typeId() {
        return typeId;
    }
}
