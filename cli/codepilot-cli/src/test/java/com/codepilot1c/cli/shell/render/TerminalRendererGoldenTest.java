/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.Test;

public class TerminalRendererGoldenTest {
    private static final String ESC = "\u001B[";
    private static final UnaryOperator<String> IDENTITY = UnaryOperator.identity();

    @Test public void plainMarkdownGoldenPreservesTextAcrossArbitraryChunks() {
        StringBuilder output = new StringBuilder();
        TerminalRenderer renderer = new TerminalRenderer(output, RenderConfig.plain(IDENTITY));
        StreamingTextSink sink = renderer.openText();

        sink.append("# Заго");
        sink.append("ловок 中文\r\nОбычный **жир");
        sink.append("ный** и `ко");
        sink.append("д` — 😀\n``");
        sink.append("`java\nSystem.out.println(\"Привет 世界\");\n`");
        sink.append("``\nЦена - 5; - это не diff\nнепарная **метка\n");
        sink.end();

        assertEquals("""
                Заголовок 中文
                Обычный жирный и код — 😀
                System.out.println("Привет 世界");
                Цена - 5; - это не diff
                непарная **метка
                """, output.toString());
        assertFalse(output.toString().contains("\u001B"));
    }

    @Test public void ansiMarkdownGoldenStylesOnlyRecognizedMarkdown() {
        StringBuilder output = new StringBuilder();
        TerminalRenderer renderer = new TerminalRenderer(output, RenderConfig.ansi(IDENTITY));
        StreamingTextSink sink = renderer.openText();

        sink.append("## Head\nText **bo");
        sink.append("ld** and `code`\n```txt\nwide 中文\n```\nordinary - minus\n");
        sink.end();

        assertEquals("\u001B[1;36mHead\u001B[0m\n"
                + "Text \u001B[1mbold\u001B[0m and \u001B[36mcode\u001B[0m\n"
                + "\u001B[36mwide 中文\u001B[0m\n"
                + "ordinary - minus\n", output.toString());
    }

    @Test public void ansiAndPlainDiffGoldensDistinguishHeadersFromChanges() {
        String diff = "diff --git a/a b/a\nindex 1..2 100644\n--- a/a\n+++ b/a\n"
                + "@@ -1 +1 @@\n-old\n+new\n context\n";

        StringBuilder ansi = new StringBuilder();
        new TerminalRenderer(ansi, RenderConfig.ansi(IDENTITY)).presentDiff(diff);
        assertEquals("\u001B[36mdiff --git a/a b/a\u001B[0m\n"
                + "\u001B[36mindex 1..2 100644\u001B[0m\n"
                + "\u001B[36m--- a/a\u001B[0m\n"
                + "\u001B[36m+++ b/a\u001B[0m\n"
                + "\u001B[36m@@ -1 +1 @@\u001B[0m\n"
                + "\u001B[31m-old\u001B[0m\n"
                + "\u001B[32m+new\u001B[0m\n"
                + " context\n", ansi.toString());

        StringBuilder plain = new StringBuilder();
        new TerminalRenderer(plain, RenderConfig.plain(IDENTITY)).presentDiff(diff);
        assertEquals(diff, plain.toString());

        StringBuilder ordinary = new StringBuilder();
        StreamingTextSink text = new TerminalRenderer(ordinary, RenderConfig.ansi(IDENTITY)).openText();
        text.append("- ordinary Markdown-like minus\nvalue - delta\n");
        text.end();
        assertEquals("- ordinary Markdown-like minus\nvalue - delta\n", ordinary.toString());
    }

    @Test public void diffFenceStateSurvivesDelimiterAndContentBoundaries() {
        StringBuilder output = new StringBuilder();
        StreamingTextSink sink = new TerminalRenderer(output, RenderConfig.ansi(IDENTITY)).openText();

        sink.append("``");
        sink.append("`di");
        sink.append("ff\n-");
        sink.append("before\n+");
        sink.append("after\n``");
        sink.append("`\nafter - fence\n");
        sink.end();

        assertEquals("\u001B[31m-before\u001B[0m\n"
                + "\u001B[32m+after\u001B[0m\n"
                + "after - fence\n", output.toString());
    }

    @Test public void cancellationFlushesPartialCodeAndResetsFenceState() {
        StringBuilder output = new StringBuilder();
        TerminalRenderer renderer = new TerminalRenderer(output, RenderConfig.ansi(IDENTITY));
        StreamingTextSink sink = renderer.openText();

        sink.append("before\r");
        sink.append("\n```diff\r\n-partial");
        renderer.cancel();

        assertTrue(sink.isFinished());
        assertEquals("before\n\u001B[31m-partial\u001B[0m\n", output.toString());
        assertThrows(IllegalStateException.class, () -> sink.append("leak"));

        StreamingTextSink next = renderer.openText();
        next.append("- ordinary\n");
        next.end();
        assertEquals("before\n\u001B[31m-partial\u001B[0m\n- ordinary\n", output.toString());
    }

    @Test public void everyUntrustedToolFieldAndStreamLineIsRedacted() {
        List<String> observed = new ArrayList<>();
        UnaryOperator<String> redactor = value -> {
            observed.add(value);
            return value.replace("SECRET", "<redacted>");
        };
        StringBuilder output = new StringBuilder();
        TerminalRenderer renderer = new TerminalRenderer(output, RenderConfig.ansi(redactor));

        StreamingTextSink text = renderer.openText();
        text.append("# SECRET heading\n**SECRET bold** and `SECRET code`\n```diff\n-SECRET old\n```");
        text.end();
        renderer.presentDiff("+SECRET new");
        renderer.presentToolCall(new ToolCallPresentation(
                "id-SECRET", "tool-SECRET", "{payload: SECRET}\nsecond line"));
        renderer.presentToolResult(ToolResultPresentation.success(
                "id-SECRET", "tool-SECRET", "result SECRET"));
        renderer.presentToolResult(ToolResultPresentation.error(
                "id-SECRET", "tool-SECRET", "failure SECRET"));

        assertFalse(output.toString(), output.toString().contains("SECRET"));
        assertTrue(output.toString().contains("<redacted>"));
        assertTrue(observed.contains("# SECRET heading"));
        assertTrue(observed.contains("**SECRET bold** and `SECRET code`"));
        assertTrue(observed.contains("-SECRET old"));
        assertTrue(observed.contains("+SECRET new"));
        assertTrue(observed.contains("id-SECRET"));
        assertTrue(observed.contains("tool-SECRET"));
        assertTrue(observed.contains("{payload: SECRET}\nsecond line"));
        assertTrue(observed.contains("result SECRET"));
        assertTrue(observed.contains("failure SECRET"));
    }

    @Test public void toolPresenterGoldenIsStableConciseAndUnicodeSafe() {
        StringBuilder output = new StringBuilder();
        TerminalRenderer renderer = new TerminalRenderer(output, RenderConfig.plain(IDENTITY));

        renderer.presentToolCall(new ToolCallPresentation("42", "поиск", "  строка\n\t世界  "));
        renderer.presentToolResult(ToolResultPresentation.success("42", "поиск", "готово 😀"));
        renderer.presentToolResult(ToolResultPresentation.error("43", "запись", "ошибка\r\nдетали"));
        renderer.presentToolCall(new ToolCallPresentation("44", "long",
                "x".repeat(239) + "😀tail"));

        assertEquals("tool-call поиск [42]: строка 世界\n"
                + "tool-result поиск [42] ok: готово 😀\n"
                + "tool-result запись [43] error: ошибка детали\n"
                + "tool-call long [44]: " + "x".repeat(239) + "…\n", output.toString());
    }

    @Test public void passedCapabilitiesSelectExactPlainModeForNoColorAndDumbTerminals() {
        assertEquals(RenderMode.ANSI,
                RenderConfig.forCapabilities(true, false, false, IDENTITY).mode());
        assertEquals(RenderMode.PLAIN,
                RenderConfig.forCapabilities(true, true, false, IDENTITY).mode());
        assertEquals(RenderMode.PLAIN,
                RenderConfig.forCapabilities(true, false, true, IDENTITY).mode());
        assertEquals(RenderMode.PLAIN,
                RenderConfig.forCapabilities(false, false, false, IDENTITY).mode());
    }
}
