package com.codepilot1c.core.tools.diagnostics;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.observability.EdtLogLine;
import com.codepilot1c.core.edt.observability.EdtLogTailService;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@ToolMeta(name = "tail_edt_logs", category = "diagnostics", tags = {"read-only", "edt", "diagnostics"})
public class TailEdtLogsTool extends AbstractTool {

    private static final String TOOL_NAME = "tail_edt_logs"; //$NON-NLS-1$
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string"},
                "since": {"type": "string"},
                "op_id": {"type": "string"},
                "pid": {"type": "integer"},
                "infobase": {"type": "string"},
                "errors_only": {"type": "boolean"},
                "max_lines": {"type": "integer"}
              },
              "required": [],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final EdtLogTailService service;

    public TailEdtLogsTool() {
        this(new EdtLogTailService());
    }

    public TailEdtLogsTool(EdtLogTailService service) {
        this.service = service == null ? new EdtLogTailService() : service;
    }

    @Override
    public String getDescription() {
        return "Tails EDT and CodePilot workspace logs with filters for project, op id, pid, infobase, and errors."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("tail-edt-logs"); //$NON-NLS-1$
            Map<String, Object> raw = params.getRaw();
            try {
                EdtLogTailService.Request request = new EdtLogTailService.Request(
                        asString(raw.get("project")), //$NON-NLS-1$
                        asString(raw.get("since")), //$NON-NLS-1$
                        asString(raw.get("op_id")), //$NON-NLS-1$
                        asLong(raw.get("pid")), //$NON-NLS-1$
                        asString(raw.get("infobase")), //$NON-NLS-1$
                        params.optBoolean("errors_only", false), //$NON-NLS-1$
                        params.optInt("max_lines", EdtLogTailService.DEFAULT_MAX_LINES)); //$NON-NLS-1$
                EdtLogTailService.Result result = service.tail(request);
                JsonObject payload = ObservabilityToolSupport.successEnvelope(opId, TOOL_NAME);
                JsonObject data = payload.getAsJsonObject("data"); //$NON-NLS-1$
                data.addProperty("workspace_root", //$NON-NLS-1$
                        result.workspaceRoot() == null ? "" : result.workspaceRoot().toString()); //$NON-NLS-1$
                data.add("sources", ObservabilityToolSupport.strings(result.sources())); //$NON-NLS-1$
                JsonArray lines = new JsonArray();
                for (EdtLogLine line : result.lines()) {
                    lines.add(ObservabilityToolSupport.logLineJson(line));
                }
                data.add("lines", lines); //$NON-NLS-1$
                data.addProperty("max_lines", request.maxLines()); //$NON-NLS-1$
                return ObservabilityToolSupport.success(payload);
            } catch (Exception e) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "LOG_TAIL_FAILED", //$NON-NLS-1$
                        e.getMessage(), true);
            }
        });
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).strip(); //$NON-NLS-1$
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
