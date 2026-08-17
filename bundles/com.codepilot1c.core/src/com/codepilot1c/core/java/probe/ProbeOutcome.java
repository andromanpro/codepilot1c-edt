package com.codepilot1c.core.java.probe;

/** Fixed twelve-field machine contract for compile-probe results. */
public record ProbeOutcome(
        boolean probeOk,
        boolean compiles,
        String errorCode,
        String snippetKind,
        String diagnostics,
        int errorCount,
        int warningCount,
        boolean truncated,
        long durationMs,
        int exitCode,
        String jdkSource,
        String probeMode) {

    public static final String COMPILE_ONLY = "compile_only"; //$NON-NLS-1$

    public ProbeOutcome {
        errorCode = errorCode == null ? "" : errorCode; //$NON-NLS-1$
        snippetKind = snippetKind == null ? "UNRESOLVED" : snippetKind; //$NON-NLS-1$
        diagnostics = diagnostics == null ? "" : diagnostics; //$NON-NLS-1$
        jdkSource = jdkSource == null ? "none" : jdkSource; //$NON-NLS-1$
        probeMode = COMPILE_ONLY;
    }

    public static ProbeOutcome failure(String errorCode, String diagnostics, String jdkSource) {
        return new ProbeOutcome(false, false, errorCode, "UNRESOLVED", diagnostics, //$NON-NLS-1$
                0, 0, false, 0L, -1, jdkSource, COMPILE_ONLY);
    }
}
