/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.render;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Objects;

/**
 * Dependency-free terminal renderer for streamed assistant text and tool events.
 * Callers choose ANSI capability and inject their secret redactor explicitly.
 */
public final class TerminalRenderer {
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD_CYAN = "\u001B[1;36m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final int TOOL_SUMMARY_LIMIT = 240;

    private final Appendable output;
    private final RenderConfig config;
    private final Object lock = new Object();
    private TextStream activeStream;

    public TerminalRenderer(Appendable output, RenderConfig config) {
        this.output = Objects.requireNonNull(output, "output");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Opens one Markdown-aware assistant text stream. */
    public StreamingTextSink openText() {
        return openStream(false);
    }

    /** Opens one diff stream for callers that already know the content is a diff. */
    public StreamingTextSink openDiff() {
        return openStream(true);
    }

    /** Renders a complete diff and terminates its final logical line. */
    public void presentDiff(String diff) {
        Objects.requireNonNull(diff, "diff");
        StreamingTextSink sink = openDiff();
        sink.append(diff);
        sink.end();
    }

    /** Presents a stable single-line summary of a tool invocation. */
    public void presentToolCall(ToolCallPresentation call) {
        Objects.requireNonNull(call, "call");
        synchronized (lock) {
            finishActive();
            String id = compactRedacted(call.id());
            String name = compactRedacted(call.name());
            String payload = compactRedacted(call.payload());
            write(style(CYAN, "tool-call") + " " + name + " [" + id + "]: " + payload + "\n");
        }
    }

    /** Presents a stable single-line summary of a successful or failed tool result. */
    public void presentToolResult(ToolResultPresentation result) {
        Objects.requireNonNull(result, "result");
        synchronized (lock) {
            finishActive();
            String id = compactRedacted(result.id());
            String name = compactRedacted(result.name());
            String content = compactRedacted(result.content());
            String status = result.success() ? "ok" : "error";
            String color = result.success() ? GREEN : RED;
            write(style(color, "tool-result") + " " + name + " [" + id + "] "
                    + status + ": " + content + "\n");
        }
    }

    /** Ends and flushes an active stream, if any. */
    public void finish() {
        synchronized (lock) {
            finishActive();
        }
    }

    /** Cancels and safely flushes an active stream, if any. */
    public void cancel() {
        synchronized (lock) {
            finishActive();
        }
    }

    private StreamingTextSink openStream(boolean diff) {
        synchronized (lock) {
            if (activeStream != null && !activeStream.finished) {
                throw new IllegalStateException("a renderer text stream is already active");
            }
            activeStream = new TextStream(diff);
            return activeStream;
        }
    }

    private void finishActive() {
        if (activeStream == null || activeStream.finished) return;
        activeStream.finish();
    }

    private void streamFinished(TextStream stream) {
        if (activeStream == stream) activeStream = null;
    }

    private String compactRedacted(String untrusted) {
        String value = config.redact(untrusted);
        StringBuilder compact = new StringBuilder(value.length());
        boolean previousWhitespace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                if (!previousWhitespace && compact.length() > 0) compact.append(' ');
                previousWhitespace = true;
            } else {
                compact.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
        }
        int length = compact.length();
        if (length > 0 && compact.charAt(length - 1) == ' ') compact.setLength(length - 1);
        if (compact.codePointCount(0, compact.length()) <= TOOL_SUMMARY_LIMIT) return compact.toString();
        int end = compact.offsetByCodePoints(0, TOOL_SUMMARY_LIMIT - 1);
        return compact.substring(0, end) + "…";
    }

    private String renderMarkdownLine(String line, MarkdownState state) {
        Fence fence = Fence.parse(line);
        if (state.inFence) {
            if (fence != null && fence.marker == state.fenceMarker
                    && fence.length >= state.fenceLength && fence.info.isBlank()) {
                state.closeFence();
                return null;
            }
            if (state.diffFence) return renderDiffLine(line);
            return style(CYAN, line);
        }
        if (fence != null) {
            state.openFence(fence);
            return null;
        }

        int headingEnd = headingMarkerEnd(line);
        if (headingEnd >= 0) {
            String heading = renderInline(line.substring(headingEnd), false);
            return style(BOLD_CYAN, heading);
        }
        return renderInline(line, config.mode() == RenderMode.ANSI);
    }

    private String renderInline(String line, boolean ansi) {
        StringBuilder rendered = new StringBuilder(line.length());
        int index = 0;
        while (index < line.length()) {
            if (line.charAt(index) == '`') {
                int closing = line.indexOf('`', index + 1);
                if (closing >= 0) {
                    appendStyled(rendered, CYAN, line.substring(index + 1, closing), ansi);
                    index = closing + 1;
                    continue;
                }
            }
            if (index + 1 < line.length()) {
                String marker = line.substring(index, index + 2);
                if (marker.equals("**") || marker.equals("__")) {
                    int closing = line.indexOf(marker, index + 2);
                    if (closing >= 0) {
                        appendStyled(rendered, BOLD, line.substring(index + 2, closing), ansi);
                        index = closing + 2;
                        continue;
                    }
                }
            }
            int codePoint = line.codePointAt(index);
            rendered.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
        }
        return rendered.toString();
    }

    private String renderDiffLine(String line) {
        if (config.mode() == RenderMode.PLAIN || line.isEmpty()) return line;
        if (line.startsWith("diff ") || line.startsWith("index ")
                || line.startsWith("@@") || line.startsWith("--- ")
                || line.startsWith("+++ ") || line.startsWith("*** ")
                || line.startsWith("\\ No newline")) {
            return style(CYAN, line);
        }
        if (line.charAt(0) == '+') return style(GREEN, line);
        if (line.charAt(0) == '-') return style(RED, line);
        return line;
    }

    private int headingMarkerEnd(String line) {
        int hashes = 0;
        while (hashes < line.length() && hashes < 6 && line.charAt(hashes) == '#') hashes++;
        if (hashes == 0 || hashes >= line.length()) return -1;
        char separator = line.charAt(hashes);
        if (separator != ' ' && separator != '\t') return -1;
        int content = hashes;
        while (content < line.length()
                && (line.charAt(content) == ' ' || line.charAt(content) == '\t')) content++;
        return content;
    }

    private String style(String ansi, String text) {
        if (config.mode() == RenderMode.PLAIN || text.isEmpty()) return text;
        return ansi + text + RESET;
    }

    private static void appendStyled(StringBuilder target, String ansi, String text, boolean enabled) {
        if (enabled && !text.isEmpty()) target.append(ansi).append(text).append(RESET);
        else target.append(text);
    }

    private void write(String value) {
        try {
            output.append(value);
        } catch (IOException exception) {
            throw new UncheckedIOException("terminal renderer output failed", exception);
        }
    }

    private final class TextStream implements StreamingTextSink {
        private final boolean forcedDiff;
        private final MarkdownState markdown = new MarkdownState();
        private final StringBuilder line = new StringBuilder();
        private boolean pendingCarriageReturn;
        private boolean finished;

        private TextStream(boolean forcedDiff) {
            this.forcedDiff = forcedDiff;
        }

        @Override
        public void append(String delta) {
            Objects.requireNonNull(delta, "delta");
            synchronized (lock) {
                if (finished) throw new IllegalStateException("text stream is already finished");
                for (int index = 0; index < delta.length(); index++) {
                    char character = delta.charAt(index);
                    if (pendingCarriageReturn) {
                        emitLine();
                        pendingCarriageReturn = false;
                        if (character == '\n') continue;
                    }
                    if (character == '\r') pendingCarriageReturn = true;
                    else if (character == '\n') emitLine();
                    else line.append(character);
                }
            }
        }

        @Override
        public void end() {
            synchronized (lock) {
                finish();
            }
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                finish();
            }
        }

        @Override
        public boolean isFinished() {
            synchronized (lock) {
                return finished;
            }
        }

        private void emitLine() {
            String redacted = config.redact(line.toString());
            line.setLength(0);
            String rendered = forcedDiff ? renderDiffLine(redacted)
                    : renderMarkdownLine(redacted, markdown);
            if (rendered != null) write(rendered + "\n");
        }

        private void finish() {
            if (finished) return;
            if (pendingCarriageReturn) {
                emitLine();
                pendingCarriageReturn = false;
            } else if (line.length() > 0) {
                emitLine();
            }
            markdown.closeFence();
            finished = true;
            streamFinished(this);
        }
    }

    private static final class MarkdownState {
        private boolean inFence;
        private char fenceMarker;
        private int fenceLength;
        private boolean diffFence;

        private void openFence(Fence fence) {
            inFence = true;
            fenceMarker = fence.marker;
            fenceLength = fence.length;
            String language = fence.info.strip().toLowerCase(Locale.ROOT);
            diffFence = language.equals("diff") || language.equals("patch");
        }

        private void closeFence() {
            inFence = false;
            fenceMarker = 0;
            fenceLength = 0;
            diffFence = false;
        }
    }

    private static final class Fence {
        private final char marker;
        private final int length;
        private final String info;

        private Fence(char marker, int length, String info) {
            this.marker = marker;
            this.length = length;
            this.info = info;
        }

        private static Fence parse(String line) {
            int index = 0;
            while (index < line.length() && index < 3 && line.charAt(index) == ' ') index++;
            if (index >= line.length()) return null;
            char marker = line.charAt(index);
            if (marker != '`' && marker != '~') return null;
            int end = index;
            while (end < line.length() && line.charAt(end) == marker) end++;
            int length = end - index;
            if (length < 3) return null;
            return new Fence(marker, length, line.substring(end));
        }
    }
}
