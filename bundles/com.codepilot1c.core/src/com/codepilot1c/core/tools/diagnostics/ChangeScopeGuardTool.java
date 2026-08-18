package com.codepilot1c.core.tools.diagnostics;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.edt.metadata.scope.ChangeScopeService;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Guards a mutation against collateral damage: snapshot the project sources
 * before the change, declare what the change is supposed to touch, then verify
 * that the actual diff equals that declaration.
 *
 * <p>Complements the round-trip check rather than repeating it. A round-trip
 * proves the sources still load; this proves the change did not quietly rewrite
 * anything else — the failure mode behind textual renames touching borrowed BSP
 * literals, or form edits re-serializing neighbouring objects.</p>
 */
@ToolMeta(name = "change_scope_guard", category = "diagnostics", tags = {"read-only", "edt", "diagnostics"})
public class ChangeScopeGuardTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ChangeScopeGuardTool.class);
    private static final String TOOL_NAME = "change_scope_guard"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "enum": ["snapshot", "verify"],
                  "description": "snapshot: capture the baseline BEFORE the mutation. verify: compare the current tree against that baseline."
                },
                "project": {"type": "string", "description": "EDT project name whose src tree is guarded"},
                "scope_id": {"type": "string", "description": "Label tying a verify back to its snapshot; default 'default'"},
                "expected": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "verify only: project-relative paths the mutation was supposed to touch. A trailing '/' declares a whole folder, e.g. Catalogs/Товары/"
                },
                "max_listed": {"type": "integer", "description": "Cap on paths listed per bucket, default 50"}
              },
              "required": ["command", "project"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final ChangeScopeService service;

    public ChangeScopeGuardTool() {
        this(null);
    }

    public ChangeScopeGuardTool(ChangeScopeService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Verifies that a metadata mutation changed EXACTLY the declared files and nothing else: " //$NON-NLS-1$
                + "snapshot the project src before the change, then verify against a declared scope. " //$NON-NLS-1$
                + "Catches collateral edits a green round-trip cannot see."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("scope"); //$NON-NLS-1$
            Map<String, Object> raw = params.getRaw();
            String command = asString(raw.get("command")); //$NON-NLS-1$
            String project = asString(raw.get("project")); //$NON-NLS-1$
            String scopeId = asString(raw.get("scope_id")); //$NON-NLS-1$
            if (scopeId.isBlank()) {
                scopeId = "default"; //$NON-NLS-1$
            }
            if (project.isBlank()) {
                return failure(opId, "INVALID_ARGUMENT", "project is required"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            try {
                ChangeScopeService svc = service != null ? service : new ChangeScopeService(workspaceRoot());
                if ("snapshot".equals(command)) { //$NON-NLS-1$
                    ChangeScopeService.Snapshot snap = svc.capture(project, scopeId);
                    Path stored = svc.store(snap);
                    JsonObject payload = new JsonObject();
                    payload.addProperty("command", "snapshot"); //$NON-NLS-1$ //$NON-NLS-2$
                    payload.addProperty("project", project); //$NON-NLS-1$
                    payload.addProperty("scope_id", scopeId); //$NON-NLS-1$
                    payload.addProperty("files_hashed", snap.files.size()); //$NON-NLS-1$
                    payload.addProperty("baseline", stored.toString()); //$NON-NLS-1$
                    LOG.info("[%s] snapshot project=%s scope=%s files=%d", //$NON-NLS-1$
                            opId, project, scopeId, snap.files.size());
                    return ok(payload);
                }
                if ("verify".equals(command)) { //$NON-NLS-1$
                    List<String> expected = asStringList(raw.get("expected")); //$NON-NLS-1$
                    ChangeScopeService.Snapshot baseline = svc.load(project, scopeId);
                    ChangeScopeService.Snapshot current = svc.capture(project, scopeId);
                    ChangeScopeService.Verdict v = svc.verify(baseline, current, expected);
                    int cap = Math.max(params.optInt("max_listed", 50), 1); //$NON-NLS-1$
                    JsonObject payload = new JsonObject();
                    payload.addProperty("command", "verify"); //$NON-NLS-1$ //$NON-NLS-2$
                    payload.addProperty("project", project); //$NON-NLS-1$
                    payload.addProperty("scope_id", scopeId); //$NON-NLS-1$
                    payload.addProperty("clean", v.isClean()); //$NON-NLS-1$
                    payload.addProperty("files_scanned", v.filesScanned); //$NON-NLS-1$
                    payload.add("expected_and_changed", list(v.expectedAndChanged, cap)); //$NON-NLS-1$
                    payload.add("expected_but_untouched", list(v.expectedButUntouched, cap)); //$NON-NLS-1$
                    payload.add("unexpectedly_changed", list(v.unexpectedlyChanged, cap)); //$NON-NLS-1$
                    payload.add("unexpectedly_added", list(v.unexpectedlyAdded, cap)); //$NON-NLS-1$
                    payload.add("unexpectedly_removed", list(v.unexpectedlyRemoved, cap)); //$NON-NLS-1$
                    if (!v.isClean()) {
                        payload.addProperty("verdict", //$NON-NLS-1$
                                "OUT_OF_SCOPE: фактический дифф не совпал с заявленным намерением"); //$NON-NLS-1$
                    }
                    LOG.info("[%s] verify project=%s scope=%s clean=%s unexpected=%d untouched=%d", //$NON-NLS-1$
                            opId, project, scopeId, v.isClean(),
                            v.unexpectedlyChanged.size() + v.unexpectedlyAdded.size() + v.unexpectedlyRemoved.size(),
                            v.expectedButUntouched.size());
                    return ok(payload);
                }
                return failure(opId, "INVALID_ARGUMENT", "command must be 'snapshot' or 'verify'"); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[%s] change_scope_guard failed: %s", opId, String.valueOf(e.getMessage())); //$NON-NLS-1$
                return failure(opId, "SCOPE_GUARD_FAILED", String.valueOf(e.getMessage())); //$NON-NLS-1$
            }
        });
    }

    private static Path workspaceRoot() {
        return ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
    }

    private static JsonArray list(List<String> values, int cap) {
        JsonArray arr = new JsonArray();
        int i = 0;
        for (String v : values) {
            if (i++ >= cap) {
                arr.add("… ещё " + (values.size() - cap)); //$NON-NLS-1$
                break;
            }
            arr.add(v);
        }
        return arr;
    }

    private static List<String> asStringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                String s = String.valueOf(o).strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static ToolResult ok(JsonObject payload) {
        return ToolResult.success(new GsonBuilder().setPrettyPrinting().create().toJson(payload),
                ToolResult.ToolResultType.CODE);
    }

    private ToolResult failure(String opId, String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("op_id", opId); //$NON-NLS-1$
        error.addProperty("tool", TOOL_NAME); //$NON-NLS-1$
        error.addProperty("code", code); //$NON-NLS-1$
        error.addProperty("message", message); //$NON-NLS-1$
        return ToolResult.failure("[" + code + "] " + message + "\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + new GsonBuilder().setPrettyPrinting().create().toJson(error));
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).strip(); //$NON-NLS-1$
    }
}
