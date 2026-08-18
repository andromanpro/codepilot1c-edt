package com.codepilot1c.core.java.probe;

import com.google.gson.JsonObject;

/** Serializes {@link ProbeOutcome} without optional or scenario-dependent keys. */
public final class ProbePayload {

    private ProbePayload() {
    }

    public static JsonObject toJson(ProbeOutcome outcome) {
        JsonObject payload = new JsonObject();
        payload.addProperty("probe_ok", outcome.probeOk()); //$NON-NLS-1$
        payload.addProperty("compiles", outcome.compiles()); //$NON-NLS-1$
        payload.addProperty("error_code", outcome.errorCode()); //$NON-NLS-1$
        payload.addProperty("snippet_kind", outcome.snippetKind()); //$NON-NLS-1$
        payload.addProperty("diagnostics", outcome.diagnostics()); //$NON-NLS-1$
        payload.addProperty("error_count", outcome.errorCount()); //$NON-NLS-1$
        payload.addProperty("warning_count", outcome.warningCount()); //$NON-NLS-1$
        payload.addProperty("truncated", outcome.truncated()); //$NON-NLS-1$
        payload.addProperty("duration_ms", outcome.durationMs()); //$NON-NLS-1$
        payload.addProperty("exit_code", outcome.exitCode()); //$NON-NLS-1$
        payload.addProperty("jdk_source", outcome.jdkSource()); //$NON-NLS-1$
        payload.addProperty("probe_mode", outcome.probeMode()); //$NON-NLS-1$
        return payload;
    }
}
