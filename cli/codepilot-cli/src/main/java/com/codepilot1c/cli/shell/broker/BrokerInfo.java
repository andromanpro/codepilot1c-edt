/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.broker;

import java.util.Objects;

/** Safe, allowlisted metadata returned by the EDT LLM broker probe. */
public record BrokerInfo(int schemaVersion, int maxSchemaVersion, boolean chat,
        boolean streaming, Provider provider) {

    public BrokerInfo {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        if (maxSchemaVersion < schemaVersion) {
            throw new IllegalArgumentException("maxSchemaVersion must cover schemaVersion");
        }
        Objects.requireNonNull(provider, "provider");
    }

    /** Provider fields explicitly allowlisted by the broker contract. */
    public record Provider(String id, String name, String type, String model,
            boolean streamingEnabled) {
        public Provider {
            id = safe(id);
            name = safe(name);
            type = safe(type);
            model = safe(model);
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
