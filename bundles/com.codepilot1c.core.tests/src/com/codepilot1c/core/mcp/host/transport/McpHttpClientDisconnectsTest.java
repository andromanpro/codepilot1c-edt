package com.codepilot1c.core.mcp.host.transport;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.IOException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;

import org.junit.Test;

public class McpHttpClientDisconnectsTest {

    @Test
    public void recognizesExpectedPlatformMessageVariants() {
        String[] messages = {
            "Broken pipe", //$NON-NLS-1$
            "BROKEN PIPE (write failed)", //$NON-NLS-1$
            "Connection reset", //$NON-NLS-1$
            "Connection reset by peer: socket write error", //$NON-NLS-1$
            "Connection aborted", //$NON-NLS-1$
            "Software caused connection abort", //$NON-NLS-1$
            "An established connection was aborted by the software in your host machine", //$NON-NLS-1$
            "An existing connection was forcibly closed by the remote host", //$NON-NLS-1$
            "Closed channel" //$NON-NLS-1$
        };

        for (String message : messages) {
            SocketException disconnect = new SocketException(message);
            assertSame(message, disconnect, McpHttpClientDisconnects.findExpected(
                    new IllegalStateException("wrapped handler failure", disconnect))); //$NON-NLS-1$
        }
    }

    @Test
    public void recognizesTypedClosedChannelWithoutMessage() {
        ClosedChannelException failure = new ClosedChannelException();

        assertSame(failure, McpHttpClientDisconnects.findExpected(failure));
    }

    @Test
    public void findsExpectedIOExceptionThroughWrappedCauseChain() {
        SocketException disconnect = new SocketException("Connection reset by peer"); //$NON-NLS-1$
        IOException ioWrapper = new IOException("response write failed", disconnect); //$NON-NLS-1$
        RuntimeException handlerWrapper = new IllegalStateException("handler failed", ioWrapper); //$NON-NLS-1$

        assertSame(disconnect, McpHttpClientDisconnects.findExpected(handlerWrapper));
    }

    @Test
    public void rejectsUnexpectedIoAndRuntimeFailures() {
        assertNull(McpHttpClientDisconnects.findExpected(null));
        assertNull(McpHttpClientDisconnects.findExpected(new IOException("Read timed out"))); //$NON-NLS-1$
        assertNull(McpHttpClientDisconnects.findExpected(new SocketException("Connection refused"))); //$NON-NLS-1$
        assertNull(McpHttpClientDisconnects.findExpected(new IOException("Broken pipeline state"))); //$NON-NLS-1$
        assertNull(McpHttpClientDisconnects.findExpected(new IOException("Channel closed for maintenance"))); //$NON-NLS-1$
        assertNull(McpHttpClientDisconnects.findExpected(new RuntimeException("Broken pipe"))); //$NON-NLS-1$
        assertNull(McpHttpClientDisconnects.findExpected(
                new RuntimeException("Broken pipe", new IOException("Read timed out")))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(McpHttpClientDisconnects.findExpected(
                new IllegalStateException("handler failed", new IOException("Unexpected end of data")))); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
