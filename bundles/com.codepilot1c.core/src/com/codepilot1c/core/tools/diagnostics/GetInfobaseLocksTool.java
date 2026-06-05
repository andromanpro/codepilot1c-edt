package com.codepilot1c.core.tools.diagnostics;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.observability.InfobaseLockService;
import com.codepilot1c.core.edt.observability.InfobaseLockSnapshot;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

@ToolMeta(name = "get_infobase_locks", category = "diagnostics", tags = {"read-only", "edt", "diagnostics"})
public class GetInfobaseLocksTool extends AbstractTool {

    private static final String TOOL_NAME = "get_infobase_locks"; //$NON-NLS-1$
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path_or_connection": {"type": "string"},
                "include_evidence": {"type": "boolean"}
              },
              "required": ["path_or_connection"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final InfobaseLockService service;

    public GetInfobaseLocksTool() {
        this(new InfobaseLockService());
    }

    public GetInfobaseLocksTool(InfobaseLockService service) {
        this.service = service == null ? new InfobaseLockService() : service;
    }

    @Override
    public String getDescription() {
        return "Reports file infobase locks and related 1C processes for a path or connection string."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("ib-locks"); //$NON-NLS-1$
            Map<String, Object> raw = params.getRaw();
            String pathOrConnection = asString(raw.get("path_or_connection")); //$NON-NLS-1$
            if (pathOrConnection.isBlank()) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "INVALID_ARGUMENT", //$NON-NLS-1$
                        "path_or_connection is required", true); //$NON-NLS-1$
            }
            boolean includeEvidence = params.optBoolean("include_evidence", true); //$NON-NLS-1$
            try {
                InfobaseLockSnapshot snapshot = service.inspect(pathOrConnection);
                JsonObject payload = ObservabilityToolSupport.successEnvelope(opId, TOOL_NAME);
                payload.getAsJsonObject("data").add("lock", //$NON-NLS-1$ //$NON-NLS-2$
                        ObservabilityToolSupport.lockJson(snapshot, includeEvidence));
                return ObservabilityToolSupport.success(payload);
            } catch (Exception e) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "LOCK_INSPECTION_FAILED", //$NON-NLS-1$
                        e.getMessage(), true);
            }
        });
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).strip(); //$NON-NLS-1$
    }
}
