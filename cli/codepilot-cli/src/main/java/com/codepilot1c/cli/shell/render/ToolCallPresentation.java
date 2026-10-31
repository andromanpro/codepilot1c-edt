/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.render;

import java.util.Objects;

/** Renderer-owned, runtime-independent view of a tool invocation. */
public record ToolCallPresentation(String id, String name, String payload) {
    public ToolCallPresentation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(payload, "payload");
    }
}
