/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.session;

import java.util.List;
import java.util.Objects;

import com.codepilot1c.runtime.agent.AgentMessage;

/** Valid messages and safe context-comparison data returned by a resume. */
public record ResumedSession(
        SessionMetadata metadata,
        List<AgentMessage> messages,
        SessionMismatch mismatch) {

    public ResumedSession {
        Objects.requireNonNull(metadata, "metadata"); //$NON-NLS-1$
        messages = List.copyOf(messages);
        Objects.requireNonNull(mismatch, "mismatch"); //$NON-NLS-1$
    }
}
