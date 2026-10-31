/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class JLineTerminalFactoryTest {
    @Test public void abortReadUnblocksJLineAndLeavesReaderReusable() throws Exception {
        try (PipedInputStream input = new PipedInputStream();
                PipedOutputStream inputWriter = new PipedOutputStream(input)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            var executor = Executors.newSingleThreadExecutor();
            try (ShellTerminal terminal = new JLineTerminalFactory(input, output, () -> false).open()) {
                var blocked = executor.submit(() -> terminal.readLine("approval> "));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!output.toString(StandardCharsets.UTF_8).contains("approval> ")
                        && System.nanoTime() < deadline) Thread.onSpinWait();
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("approval> "));

                terminal.abortRead();

                try {
                    blocked.get(2, TimeUnit.SECONDS);
                    throw new AssertionError("JLine read was not interrupted");
                } catch (ExecutionException expected) {
                    assertTrue(expected.getCause() instanceof TerminalInterruptedException);
                }
                inputWriter.write("next\n".getBytes(StandardCharsets.UTF_8));
                inputWriter.flush();
                assertEquals("next", terminal.readLine("codepilot> "));
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
