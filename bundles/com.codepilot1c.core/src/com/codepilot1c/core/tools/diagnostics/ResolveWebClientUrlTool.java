package com.codepilot1c.core.tools.diagnostics;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService.WebClientInfo;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ActiveProjectSupport;
import com.codepilot1c.core.tools.ProviderContextResolver;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Resolves the running 1C web client URL (and designer URL) of a project's standalone-server
 * infobase so a browser/Playwright session can navigate to it and verify the live UI.
 *
 * <p>Read-only. Also reports the standalone server state and a soft multimodal check: by default the
 * caller is assumed to be vision-capable; when the active model name does not confirm image input,
 * a {@code vision_hint} suggests switching to a multimodal model or using text-based verification.</p>
 */
@ToolMeta(name = "resolve_web_client_url", category = "diagnostics",
        tags = {"read-only", "edt", "diagnostics"})
public class ResolveWebClientUrlTool extends AbstractTool {

    private static final String TOOL_NAME = "resolve_web_client_url"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project whose standalone-server infobase web client URL is needed. Optional: if omitted, the active editor project (or the single open project) is used."}
              },
              "required": [],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final EdtRuntimeService runtimeService;
    private final ProviderContextResolver providerContextResolver;

    public ResolveWebClientUrlTool() {
        this(new EdtRuntimeService(), new ProviderContextResolver());
    }

    ResolveWebClientUrlTool(EdtRuntimeService runtimeService, ProviderContextResolver providerContextResolver) {
        this.runtimeService = runtimeService == null ? new EdtRuntimeService() : runtimeService;
        this.providerContextResolver =
                providerContextResolver == null ? new ProviderContextResolver() : providerContextResolver;
    }

    @Override
    public String getDescription() {
        return "Resolves the running 1C web client URL (and designer URL) of a project's standalone-server infobase for browser-based UI verification."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("web-client-url"); //$NON-NLS-1$
            try {
                String projectName = resolveProjectName(params);
                if (projectName == null || projectName.isBlank()) {
                    return ObservabilityToolSupport.failure(opId, TOOL_NAME, "PROJECT_NOT_RESOLVED", //$NON-NLS-1$
                            "projectName could not be resolved automatically. Open projects: " //$NON-NLS-1$
                                    + ActiveProjectSupport.openProjectNames()
                                    + ". Pass projectName explicitly, or open the target project in the EDT editor.", //$NON-NLS-1$
                            true);
                }

                WebClientInfo info = runtimeService.resolveWebClientInfo(projectName);
                if (!info.available()) {
                    return ObservabilityToolSupport.failure(opId, TOOL_NAME, "WEB_CLIENT_UNAVAILABLE", //$NON-NLS-1$
                            info.message(), true);
                }

                JsonObject payload = ObservabilityToolSupport.successEnvelope(opId, TOOL_NAME);
                JsonObject data = payload.getAsJsonObject("data"); //$NON-NLS-1$
                data.addProperty("project", projectName); //$NON-NLS-1$
                data.addProperty("web_client_url", info.webClientUrl()); //$NON-NLS-1$
                data.addProperty("designer_url", info.designerUrl()); //$NON-NLS-1$
                data.addProperty("server_name", info.serverName()); //$NON-NLS-1$
                data.addProperty("server_state", info.serverState()); //$NON-NLS-1$
                data.addProperty("server_running", info.serverRunning()); //$NON-NLS-1$

                applyMultimodalGuard(data);
                data.addProperty("usage_hint", usageHint(info)); //$NON-NLS-1$

                return ObservabilityToolSupport.success(payload);
            } catch (Exception e) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "WEB_CLIENT_URL_FAILED", //$NON-NLS-1$
                        e.getMessage(), true);
            }
        });
    }

    private String resolveProjectName(ToolParameters params) {
        Object raw = params.getRaw().get("projectName"); //$NON-NLS-1$
        String explicit = raw == null ? null : String.valueOf(raw).trim();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return ActiveProjectSupport.resolveActiveProjectName();
    }

    /**
     * Soft multimodal check honoring the "assume multimodal by default" policy: the active model is
     * treated as vision-capable unless its name clearly marks a text-only family (so new frontier
     * models like {@code gpt-5.x} are not falsely flagged). Only a recognized text-only model yields
     * {@code vision_confirmed=false} plus an actionable hint. Never blocks.
     */
    private void applyMultimodalGuard(JsonObject data) {
        String model = null;
        try {
            LlmProviderConfig config = providerContextResolver.resolveActiveProviderConfig();
            model = config == null ? null : config.getModel();
        } catch (Exception e) {
            model = null;
        }
        boolean knownVision = ProviderCapabilities.inferImageInputFromModel(model);
        boolean textOnly = !knownVision && looksTextOnly(model);
        boolean visionConfirmed = !textOnly; // default: assume multimodal
        String basis = knownVision ? "known-multimodal" //$NON-NLS-1$
                : (textOnly ? "text-only" : "assumed-multimodal"); //$NON-NLS-1$ //$NON-NLS-2$
        data.addProperty("active_model", model == null ? "" : model); //$NON-NLS-1$ //$NON-NLS-2$
        data.addProperty("vision_confirmed", visionConfirmed); //$NON-NLS-1$
        data.addProperty("vision_basis", basis); //$NON-NLS-1$
        data.addProperty("vision_hint", textOnly //$NON-NLS-1$
                ? "The active model name ('" + (model == null ? "" : model) //$NON-NLS-1$ //$NON-NLS-2$
                        + "') looks text-only. Screenshot-based verification needs a multimodal model: " //$NON-NLS-1$
                        + "switch to a vision-capable model, or verify via text-based DOM/markup instead of screenshots." //$NON-NLS-1$
                : ""); //$NON-NLS-1$
    }

    /** Conservative text-only detector — matches only well-known non-vision families. */
    private static boolean looksTextOnly(String model) {
        if (model == null || model.isBlank()) {
            return false; // unknown → assume multimodal
        }
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("coder") || lower.contains("code-") || lower.contains("codestral") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || lower.contains("codellama") || lower.contains("starcoder") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("deepseek-coder") || lower.contains("embed") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("rerank") || lower.contains("whisper") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.contains("-tts") || lower.contains("text-moderation"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String usageHint(WebClientInfo info) {
        StringBuilder hint = new StringBuilder();
        if (!info.serverRunning()) {
            hint.append("Standalone server is '").append(info.serverState()) //$NON-NLS-1$
                    .append("': update the infobase and start the server before verifying. "); //$NON-NLS-1$
        }
        hint.append("Open web_client_url in a configured browser MCP (e.g. Playwright), sign in with the ") //$NON-NLS-1$
                .append("infobase credentials, exercise the changed UI, capture a screenshot, and verify the result."); //$NON-NLS-1$
        return hint.toString();
    }
}
