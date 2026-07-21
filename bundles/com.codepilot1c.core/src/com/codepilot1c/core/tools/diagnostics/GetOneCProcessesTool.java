package com.codepilot1c.core.tools.diagnostics;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.observability.OneCProcessInspectionService;
import com.codepilot1c.core.edt.observability.OneCProcessSnapshot;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@ToolMeta(name = "get_1c_processes", category = "diagnostics", tags = {"read-only", "edt", "diagnostics"})
public class GetOneCProcessesTool extends AbstractTool {

    private static final String TOOL_NAME = "get_1c_processes"; //$NON-NLS-1$
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "include_open_files": {"type": "boolean"},
                "include_ports": {"type": "boolean"}
              },
              "required": [],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final OneCProcessInspectionService service;

    public GetOneCProcessesTool() {
        this(new OneCProcessInspectionService());
    }

    public GetOneCProcessesTool(OneCProcessInspectionService service) {
        this.service = service == null ? new OneCProcessInspectionService() : service;
    }

    @Override
    public String getDescription() {
        return "Lists 1C/EDT runtime processes with PID, parent, command line, ports, and inferred infobase paths."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("get-1c-procs"); //$NON-NLS-1$
            boolean includeOpenFiles = params.optBoolean("include_open_files", false); //$NON-NLS-1$
            boolean includePorts = params.optBoolean("include_ports", true); //$NON-NLS-1$
            try {
                JsonObject payload = ObservabilityToolSupport.successEnvelope(opId, TOOL_NAME);
                JsonObject data = payload.getAsJsonObject("data"); //$NON-NLS-1$
                data.addProperty("include_open_files", includeOpenFiles); //$NON-NLS-1$
                data.addProperty("include_ports", includePorts); //$NON-NLS-1$
                JsonArray processes = new JsonArray();
                for (OneCProcessSnapshot snapshot : service.inspect()) {
                    processes.add(ObservabilityToolSupport.processJson(snapshot, includePorts));
                }
                data.add("processes", processes); //$NON-NLS-1$
                return ObservabilityToolSupport.success(payload);
            } catch (Exception e) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "PROCESS_INSPECTION_FAILED", //$NON-NLS-1$
                        e.getMessage(), true);
            }
        });
    }
}
