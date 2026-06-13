package com.codepilot1c.core.tools.diagnostics;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService.AccessSettings;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ActiveProjectSupport;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Returns the EDT-stored infobase access credentials (user name and password) so an automated
 * browser/Playwright session can sign in to the 1C web client without asking the user to retype
 * them. Read-only against EDT's infobase access settings.
 *
 * <p><b>Sensitive:</b> the password is returned in plaintext by design (the caller needs it to log
 * in). It MUST be used only for the immediate login and never echoed back into the final report,
 * logs, or any persisted artifact. When EDT has no stored credentials, the caller should ask the
 * user to provide them in chat.</p>
 */
@ToolMeta(name = "get_infobase_credentials", category = "diagnostics",
        tags = {"read-only", "edt", "diagnostics", "sensitive"})
public class GetInfobaseCredentialsTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetInfobaseCredentialsTool.class);

    private static final String TOOL_NAME = "get_infobase_credentials"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project whose infobase access credentials are needed. Optional: if omitted, the active editor project (or the single open project) is used."}
              },
              "required": [],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final EdtRuntimeService runtimeService;

    public GetInfobaseCredentialsTool() {
        this(new EdtRuntimeService());
    }

    GetInfobaseCredentialsTool(EdtRuntimeService runtimeService) {
        this.runtimeService = runtimeService == null ? new EdtRuntimeService() : runtimeService;
    }

    @Override
    public String getDescription() {
        return "Returns EDT-stored infobase login/password for automated 1C web client sign-in. Sensitive: use only to log in, never echo back."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("infobase-creds"); //$NON-NLS-1$
            String projectName = resolveProjectName(params);
            if (projectName == null || projectName.isBlank()) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "PROJECT_NOT_RESOLVED", //$NON-NLS-1$
                        "projectName could not be resolved automatically. Open projects: " //$NON-NLS-1$
                                + ActiveProjectSupport.openProjectNames()
                                + ". Pass projectName explicitly, or open the target project in the EDT editor.", //$NON-NLS-1$
                        true);
            }

            AccessSettings settings;
            try {
                settings = runtimeService.resolveAccessSettings(projectName);
            } catch (Exception e) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "CREDENTIALS_LOOKUP_FAILED", //$NON-NLS-1$
                        e.getMessage(), true);
            }
            if (settings == null) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "CREDENTIALS_NOT_DEFINED", //$NON-NLS-1$
                        "No infobase access credentials are stored in EDT for project '" + projectName //$NON-NLS-1$
                                + "'. Ask the user to provide the web client login and password in chat, " //$NON-NLS-1$
                                + "or to set infobase access in EDT.", //$NON-NLS-1$
                        true);
            }

            String authKind = settings.isOsAuthentication() ? "os" //$NON-NLS-1$
                    : settings.isInfobaseAuthentication() ? "infobase" //$NON-NLS-1$
                    : "additional"; //$NON-NLS-1$
            // Log only non-secret metadata; the password is never written to logs.
            LOG.info("[%s] resolved infobase credentials project=%s auth_kind=%s", opId, //$NON-NLS-1$
                    LogSanitizer.truncate(projectName, 200), authKind);

            JsonObject payload = ObservabilityToolSupport.successEnvelope(opId, TOOL_NAME);
            JsonObject data = payload.getAsJsonObject("data"); //$NON-NLS-1$
            data.addProperty("project", projectName); //$NON-NLS-1$
            data.addProperty("auth_kind", authKind); //$NON-NLS-1$
            data.addProperty("user_name", nullToEmpty(settings.getUserName())); //$NON-NLS-1$
            data.addProperty("password", nullToEmpty(settings.getPassword())); //$NON-NLS-1$
            data.addProperty("additional_parameters", nullToEmpty(settings.getAdditionalParameters())); //$NON-NLS-1$
            data.addProperty("security_note", //$NON-NLS-1$
                    "Password is returned in plaintext for the immediate web client login only. " //$NON-NLS-1$
                            + "Do not echo it into the final report, logs, or any persisted artifact."); //$NON-NLS-1$
            if (settings.isOsAuthentication()) {
                data.addProperty("note", //$NON-NLS-1$
                        "OS authentication is configured: no explicit login/password — the web client uses the OS session."); //$NON-NLS-1$
            }
            return ObservabilityToolSupport.success(payload);
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
