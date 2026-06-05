package com.codepilot1c.core.edt.observability;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public interface CommandRunner {

    CommandResult run(List<String> command, Duration timeout);

    static CommandRunner processBuilder() {
        return new ProcessBuilderCommandRunner();
    }
}

final class ProcessBuilderCommandRunner implements CommandRunner {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final int STREAM_JOIN_TIMEOUT_MS = 1000;
    private static final int TAIL_BYTES = 64 * 1024;

    @Override
    public CommandResult run(List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) {
            return new CommandResult(-1, "", "Command is empty", false); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Duration effectiveTimeout = normalizeTimeout(timeout);
        Process process;
        try {
            process = new ProcessBuilder(List.copyOf(command)).start();
        } catch (IOException | RuntimeException e) {
            return new CommandResult(-1, "", message(e), false); //$NON-NLS-1$
        }

        StreamTail stdout = StreamTail.start(process.getInputStream());
        StreamTail stderr = StreamTail.start(process.getErrorStream());
        boolean timedOut = false;
        int exitCode = -1;
        try {
            boolean finished = process.waitFor(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                terminate(process);
            }
            if (!process.isAlive()) {
                exitCode = process.exitValue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timedOut = true;
            terminate(process);
        }

        String stdoutTail = stdout.finish();
        String stderrTail = stderr.finish();
        if (timedOut && stderrTail.isBlank()) {
            stderrTail = "Command timed out after " + effectiveTimeout.toMillis() + " ms"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return new CommandResult(exitCode, stdoutTail, stderrTail, timedOut);
    }

    private static Duration normalizeTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return DEFAULT_TIMEOUT;
        }
        return timeout;
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    private static final class StreamTail {
        private final TailBuffer buffer = new TailBuffer(TAIL_BYTES);
        private final Thread thread;
        private volatile IOException failure;

        private StreamTail(InputStream input) {
            this.thread = new Thread(() -> read(input), "codepilot1c-command-stream"); //$NON-NLS-1$
            this.thread.setDaemon(true);
        }

        static StreamTail start(InputStream input) {
            StreamTail tail = new StreamTail(input);
            tail.thread.start();
            return tail;
        }

        String finish() {
            try {
                thread.join(STREAM_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String result = buffer.asString();
            if (failure != null) {
                String suffix = "[stream read failed: " + failure.getMessage() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
                return result.isBlank() ? suffix : result + System.lineSeparator() + suffix;
            }
            return result;
        }

        private void read(InputStream input) {
            try (InputStream source = input) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = source.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
            } catch (IOException e) {
                failure = e;
            }
        }
    }

    private static final class TailBuffer {
        private final byte[] bytes;
        private int start;
        private int size;

        TailBuffer(int capacity) {
            this.bytes = new byte[capacity];
        }

        synchronized void write(byte[] source, int offset, int length) {
            for (int i = 0; i < length; i++) {
                if (size < bytes.length) {
                    bytes[(start + size) % bytes.length] = source[offset + i];
                    size++;
                } else {
                    bytes[start] = source[offset + i];
                    start = (start + 1) % bytes.length;
                }
            }
        }

        synchronized String asString() {
            if (size == 0) {
                return ""; //$NON-NLS-1$
            }
            byte[] result = new byte[size];
            for (int i = 0; i < size; i++) {
                result[i] = bytes[(start + i) % bytes.length];
            }
            return new String(result, StandardCharsets.UTF_8);
        }
    }
}
