package com.codepilot1c.core.tools.diagnostics;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.observability.InfobaseLockService;
import com.codepilot1c.core.edt.observability.OneCProcessInspectionService;
import com.codepilot1c.core.edt.observability.StandaloneServerObservabilityService;
import com.codepilot1c.core.edt.observability.StandaloneServerStatus;
import com.codepilot1c.core.edt.runtime.EdtRuntimeGateway;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@ToolMeta(name = "get_standalone_server_status", category = "diagnostics",
        tags = {"read-only", "edt", "diagnostics"})
public class GetStandaloneServerStatusTool extends AbstractTool {

    private static final String TOOL_NAME = "get_standalone_server_status"; //$NON-NLS-1$
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": [],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final StandaloneServerObservabilityService service;

    public GetStandaloneServerStatusTool() {
        this(new StandaloneServerObservabilityService());
    }

    public GetStandaloneServerStatusTool(StandaloneServerObservabilityService service) {
        this.service = service == null ? new StandaloneServerObservabilityService() : service;
    }

    public GetStandaloneServerStatusTool(EdtRuntimeGateway gateway,
            OneCProcessInspectionService processInspectionService, InfobaseLockService lockService) {
        this(new StandaloneServerObservabilityService(gateway, processInspectionService, lockService));
    }

    @Override
    public String getDescription() {
        return "Reports EDT standalone server state, related processes, ports, locks, and recent errors."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("standalone-status"); //$NON-NLS-1$
            try {
                StandaloneServerObservabilityService.Result result = service.inspect();
                JsonObject payload = ObservabilityToolSupport.successEnvelope(opId, TOOL_NAME);
                JsonObject data = payload.getAsJsonObject("data"); //$NON-NLS-1$
                JsonArray servers = new JsonArray();
                for (StandaloneServerStatus status : result.servers()) {
                    servers.add(ObservabilityToolSupport.standaloneStatusJson(status));
                }
                data.add("servers", servers); //$NON-NLS-1$
                data.add("last_errors", ObservabilityToolSupport.strings(result.lastErrors())); //$NON-NLS-1$
                return ObservabilityToolSupport.success(payload);
            } catch (Exception e) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "STANDALONE_STATUS_FAILED", //$NON-NLS-1$
                        e.getMessage(), true);
            }
        });
    }
}
