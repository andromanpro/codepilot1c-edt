package com.codepilot1c.core.mcp.host.transport;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/** Classifies I/O failures that mean the HTTP client has already disconnected. */
final class McpHttpClientDisconnects {

    private static final String[] DISCONNECT_MESSAGES = {
        "broken pipe", //$NON-NLS-1$
        "connection reset", //$NON-NLS-1$
        "connection reset by peer", //$NON-NLS-1$
        "connection aborted", //$NON-NLS-1$
        "software caused connection abort", //$NON-NLS-1$
        "an established connection was aborted by the software in your host machine", //$NON-NLS-1$
        "an existing connection was forcibly closed by the remote host", //$NON-NLS-1$
        "closed channel" //$NON-NLS-1$
    };

    private McpHttpClientDisconnects() {
    }

    /**
     * Returns the matching I/O cause, or {@code null} when the failure must stay
     * on the unexpected-error path.
     */
    static IOException findExpected(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof IOException ioException && isDisconnect(ioException)) {
                return ioException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean isDisconnect(IOException failure) {
        if (failure instanceof ClosedChannelException) {
            return true;
        }
        String message = failure.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.strip().toLowerCase(Locale.ROOT);
        for (String signal : DISCONNECT_MESSAGES) {
            if (matchesSignal(normalized, signal)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesSignal(String message, String signal) {
        return message.equals(signal)
                || message.startsWith(signal + ":") //$NON-NLS-1$
                || message.startsWith(signal + " (") //$NON-NLS-1$
                || message.startsWith(signal + " ["); //$NON-NLS-1$
    }
}
