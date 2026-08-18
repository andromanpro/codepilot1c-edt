/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.command;

import java.util.Map;

import com.codepilot1c.cli.render.JsonWriter;
import com.codepilot1c.cli.render.OutputMode;

final class CommandOutput {
    private CommandOutput() { }

    static void print(RootCommand root, String text, Map<String, Object> json) {
        if (root.outputMode() == OutputMode.JSON) root.services().out().println(JsonWriter.write(json));
        else root.services().out().println(text);
        root.services().out().flush();
    }

    @SuppressWarnings("unchecked")
    static void print(RootCommand root, String text, Map<String, Object> json,
            ExactSecretRedactor redactor) {
        print(root, redactor.redact(text), (Map<String, Object>) redactor.redact(json));
    }
}
