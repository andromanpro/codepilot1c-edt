package com.codepilot1c.core.tools.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.extension.ExtensionMigrationPlanRequest;
import com.codepilot1c.core.edt.extension.ExtensionMigrationPlanResult;
import com.codepilot1c.core.edt.extension.ExtensionMigrationPlanner;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;

/**
 * Dry-run-first native extension migration planner.
 */
@ToolMeta(name = "migrate_to_extension_native", category = "extension", mutating = true, tags = {"workspace", "edt"})
public class MigrateToExtensionNativeTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(MigrateToExtensionNativeTool.class);
    private static final Gson GSON = new Gson();
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "source_project": {"type":"string", "description":"Base EDT project with source objects."},
                "extension_project": {"type":"string", "description":"EDT extension project to plan native clones for."},
                "source_fqns": {"type":"array", "items":{"type":"string"}, "description":"Top-level source FQNs: Catalog.X, Bot.X, Role.X."},
                "mode": {"type":"string", "enum":["dry_run", "apply"], "description":"dry_run emits plan; apply is gated by validation tokens."},
                "validation_token": {"type":"string", "description":"Reserved for apply mode after dry-run review."}
              },
              "required": ["source_project", "extension_project", "source_fqns"]
            }
            """; //$NON-NLS-1$

    private final ExtensionMigrationPlanner planner;

    public MigrateToExtensionNativeTool() {
        this(new ExtensionMigrationPlanner());
    }

    MigrateToExtensionNativeTool(ExtensionMigrationPlanner planner) {
        this.planner = planner;
    }

    @Override
    public String getDescription() {
        return "Plans native EDT extension cloning from base objects; dry-run first, no source deletion."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("migrate-extension-native"); //$NON-NLS-1$
            try {
                Map<String, Object> p = params.getRaw();
                ExtensionMigrationPlanRequest request = new ExtensionMigrationPlanRequest(
                        asString(p.get("source_project")), //$NON-NLS-1$
                        asString(p.get("extension_project")), //$NON-NLS-1$
                        asStringList(p.get("source_fqns")), //$NON-NLS-1$
                        "apply".equalsIgnoreCase(asString(p.get("mode")))); //$NON-NLS-1$ //$NON-NLS-2$
                ExtensionMigrationPlanResult result = planner.plan(request);
                LOG.info("[%s] migrate_to_extension_native dry-run operations=%d", opId, //$NON-NLS-1$
                        Integer.valueOf(result.operationCount()));
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CODE);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }
}
