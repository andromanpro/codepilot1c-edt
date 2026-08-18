/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.InfoCmp;

/** Production JLine terminal factory with a JNI-to-plain fallback. */
public final class JLineTerminalFactory implements TerminalFactory {
    private final InputStream input;
    private final OutputStream output;
    private final BooleanSupplier interactive;

    public JLineTerminalFactory(InputStream input, OutputStream output, BooleanSupplier interactive) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.interactive = Objects.requireNonNull(interactive, "interactive");
    }

    public static JLineTerminalFactory system() {
        return new JLineTerminalFactory(System.in, System.out, () -> System.console() != null);
    }

    @Override public boolean isInteractive() {
        return interactive.getAsBoolean();
    }

    @Override public ShellTerminal open() throws IOException {
        Terminal terminal = isInteractive() ? openNativeOrPlain() : openPlain();
        boolean completed = false;
        try {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            completed = true;
            return new JLineShellTerminal(terminal, reader);
        } finally {
            if (!completed) terminal.close();
        }
    }

    private Terminal openNativeOrPlain() throws IOException {
        try {
            return TerminalBuilder.builder()
                    .system(true)
                    .provider("jni")
                    .encoding(StandardCharsets.UTF_8)
                    .build();
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            return openPlain();
        }
    }

    private Terminal openPlain() throws IOException {
        return new DumbTerminal("codepilot", "dumb", input, output, StandardCharsets.UTF_8);
    }

    private static final class JLineShellTerminal implements ShellTerminal {
        private final Terminal terminal;
        private final LineReader reader;
        private final AtomicBoolean abortRequested = new AtomicBoolean();
        private volatile boolean readActive;

        private JLineShellTerminal(Terminal terminal, LineReader reader) {
            this.terminal = terminal;
            this.reader = reader;
        }

        @Override public String readLine(String prompt) {
            readActive = true;
            try {
                if (abortRequested.getAndSet(false)) {
                    throw new TerminalInterruptedException("");
                }
                return reader.readLine(prompt);
            } catch (EndOfFileException eof) {
                return null;
            } catch (UserInterruptException interrupt) {
                throw new TerminalInterruptedException(interrupt.getPartialLine());
            } finally {
                abortRequested.set(false);
                readActive = false;
            }
        }

        @Override public void abortRead() {
            abortRequested.set(true);
            while (readActive && !reader.isReading()) Thread.onSpinWait();
            if (reader.isReading()) terminal.raise(Terminal.Signal.INT);
        }

        @Override public boolean ansiCapable() {
            return !dumb() && (terminal.getStringCapability(InfoCmp.Capability.set_a_foreground) != null
                    || terminal.getStringCapability(InfoCmp.Capability.set_foreground) != null);
        }

        @Override public boolean dumb() {
            return terminal instanceof DumbTerminal
                    || Terminal.TYPE_DUMB.equalsIgnoreCase(terminal.getType());
        }

        @Override public void println(String text) {
            terminal.writer().println(text);
        }

        @Override public void flush() {
            terminal.flush();
        }

        @Override public void close() throws IOException {
            terminal.close();
        }
    }
}
