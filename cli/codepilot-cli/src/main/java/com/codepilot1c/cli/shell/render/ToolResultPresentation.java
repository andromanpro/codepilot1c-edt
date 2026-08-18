/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.render;

import java.util.Objects;

/** Renderer-owned, runtime-independent view of a tool outcome. */
public record ToolResultPresentation(String id, String name, boolean success, String content) {
    public ToolResultPresentation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(content, "content");
    }

    public static ToolResultPresentation success(String id, String name, String content) {
        return new ToolResultPresentation(id, name, true, content);
    }

    public static ToolResultPresentation error(String id, String name, String error) {
        return new ToolResultPresentation(id, name, false, error);
    }
}
