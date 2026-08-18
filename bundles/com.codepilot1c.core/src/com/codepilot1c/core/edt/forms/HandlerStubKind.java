package com.codepilot1c.core.edt.forms;

/**
 * Distinguishes the two platform handler slots for which the service writes BSL stubs.
 *
 * <p>An {@link #EVENT_HANDLER} derives its directive and parameters from a resolved EDT
 * event. A {@link #COMMAND_ACTION} has no event to inspect: managed-form commands always
 * use the platform-defined client procedure contract.</p>
 */
public enum HandlerStubKind {
    EVENT_HANDLER,
    COMMAND_ACTION
}
