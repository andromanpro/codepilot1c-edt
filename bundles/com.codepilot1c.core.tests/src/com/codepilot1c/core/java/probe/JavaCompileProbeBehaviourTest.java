package com.codepilot1c.core.java.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.edt.observability.CommandResult;
import com.codepilot1c.core.edt.observability.CommandRunner;

public class JavaCompileProbeBehaviourTest {

    private JavaCompileProbeRunner runner;

    @Before
    public void requireJavac() {
        JdkLocator locator = JdkLocator.system(() -> null);
        Assume.assumeTrue("Behaviour tests require javac >= 17", locator.locate().available()); //$NON-NLS-1$
        runner = new JavaCompileProbeRunner(CommandRunner.isolatedProcessBuilder(), locator);
    }

    @Test
    public void expressionSnippetCompiles() {
        ProbeOutcome outcome = runner.run(true, "1 + 1", SnippetKind.EXPRESSION); //$NON-NLS-1$

        assertTrue(outcome.probeOk());
        assertTrue(outcome.compiles());
        assertEquals("EXPRESSION", outcome.snippetKind()); //$NON-NLS-1$
        assertEquals("compile_only", outcome.probeMode()); //$NON-NLS-1$
    }

    @Test
    public void projectClassReferenceFailsWithCannotFindSymbol() {
        ProbeOutcome outcome = runner.run(true,
                "ToolRegistry.getInstance()", //$NON-NLS-1$
                SnippetKind.EXPRESSION);

        assertTrue(outcome.probeOk());
        assertFalse(outcome.compiles());
        assertTrue(outcome.diagnostics(), outcome.diagnostics().contains("cannot find symbol")); //$NON-NLS-1$
    }

    @Test
    public void snippetStaticInitializerIsNeverExecuted() throws Exception {
        Path marker = Files.createTempDirectory("cp1c-probe-marker-").resolve("EXECUTED"); //$NON-NLS-1$ //$NON-NLS-2$
        String markerLiteral = marker.toString().replace("\\", "\\\\"); //$NON-NLS-1$ //$NON-NLS-2$
        String snippet = "static { try { new java.io.File(\"" + markerLiteral //$NON-NLS-1$
                + "\").createNewFile(); } catch (java.io.IOException e) { throw new RuntimeException(e); } }"; //$NON-NLS-1$
        try {
            ProbeOutcome outcome = runner.run(true, snippet, SnippetKind.DECLARATION);

            assertTrue(outcome.diagnostics(), outcome.probeOk());
            assertTrue(outcome.diagnostics(), outcome.compiles());
            assertFalse("compiled static initializer must never execute", Files.exists(marker)); //$NON-NLS-1$
        } finally {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(marker.getParent());
        }
    }

    @Test
    public void oversizedSnippetIsRejectedBeforeAnyProcess() {
        AtomicInteger calls = new AtomicInteger();
        CommandRunner recording = (command, timeout) -> {
            calls.incrementAndGet();
            return new CommandResult(0, "", "", false); //$NON-NLS-1$ //$NON-NLS-2$
        };
        JavaCompileProbeRunner guarded = new JavaCompileProbeRunner(recording,
                new JdkLocator(() -> System.getProperty("java.home"), () -> null, () -> null)); //$NON-NLS-1$

        ProbeOutcome outcome = guarded.run(true,
                "x".repeat(JavaCompileProbeRunner.MAX_SNIPPET_CHARS + 1), //$NON-NLS-1$
                SnippetKind.COMPILATION_UNIT);

        assertEquals("snippet_too_large", outcome.errorCode()); //$NON-NLS-1$
        assertEquals(0, calls.get());
    }
}
