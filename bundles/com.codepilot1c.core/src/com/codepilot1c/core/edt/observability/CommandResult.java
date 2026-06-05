package com.codepilot1c.core.edt.observability;

public record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {

    public CommandResult {
        stdout = stdout == null ? "" : stdout; //$NON-NLS-1$
        stderr = stderr == null ? "" : stderr; //$NON-NLS-1$
    }
}
