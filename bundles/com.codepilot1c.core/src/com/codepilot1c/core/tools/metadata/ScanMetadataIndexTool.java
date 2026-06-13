package com.codepilot1c.core.tools.metadata;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtAstServices;
import com.codepilot1c.core.edt.ast.MetadataIndexRequest;
import com.codepilot1c.core.edt.ast.MetadataIndexResult;
import com.codepilot1c.core.tools.ActiveProjectSupport;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Scans top-level metadata objects in EDT configuration.
 */
@ToolMeta(name = "scan_metadata_index", category = "metadata", tags = {"read-only", "workspace", "edt"})
public class ScanMetadataIndexTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project whose configuration should be scanned. Optional: if omitted, the active editor project (or the single open project) is used; otherwise the error lists available projects."},
                "scope": {"type": "string", "description": "High-level metadata scope filter such as all, catalogs, documents, commonModules"},
                "nameContains": {"type": "string", "description": "Case-insensitive object name filter for broad discovery"},
                "limit": {"type": "integer", "description": "Maximum number of index entries to return (1..1000, default 200)"},
                "language": {"type": "string", "description": "Preferred synonym language for display values (ru, en, ...)"},
                "includeModules": {"type": "boolean", "description": "Compatibility flag; does not replace detailed module inspection"}
              }
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Возвращает индекс верхнеуровневых объектов метаданных EDT по проекту с широкими фильтрами."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            try {
                String projectName = resolveProjectName(parameters);
                if (projectName == null || projectName.isBlank()) {
                    return ToolResult.failure(missingProjectMessage());
                }
                Map<String, Object> effective = new HashMap<>(parameters);
                effective.put("projectName", projectName); //$NON-NLS-1$
                MetadataIndexRequest request = MetadataIndexRequest.fromParameters(effective);
                MetadataIndexResult result = EdtAstServices.getInstance().scanMetadataIndex(request);
                JsonObject structured = GSON.toJsonTree(result).getAsJsonObject();
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS, structured);
            } catch (EdtAstException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    /**
     * Resolves the project to scan: the explicit {@code projectName} when given, otherwise the
     * active editor project, otherwise the single open workspace project. Returns {@code null} when
     * the project cannot be determined unambiguously.
     */
    private String resolveProjectName(Map<String, Object> parameters) {
        Object raw = parameters.get("projectName"); //$NON-NLS-1$
        String name = raw == null ? null : String.valueOf(raw).trim();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return ActiveProjectSupport.resolveActiveProjectName();
    }

    private String missingProjectMessage() {
        return "projectName could not be resolved automatically. Open projects: " //$NON-NLS-1$
                + ActiveProjectSupport.openProjectNames()
                + ". Pass projectName explicitly, or open the target project in the EDT editor."; //$NON-NLS-1$
    }

    private String toErrorJson(EdtAstException e) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", e.getCode().name()); //$NON-NLS-1$
        obj.addProperty("message", e.getMessage()); //$NON-NLS-1$
        obj.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
