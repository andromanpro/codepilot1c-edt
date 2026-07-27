/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Batch variant of {@link WorkspaceCopyTransformTool}.
 */
@ToolMeta(
    name = "workspace_copy_transform_batch",
    category = "file",
    mutating = true,
    tags = {"workspace"}
)
public class WorkspaceCopyTransformBatchTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(WorkspaceCopyTransformBatchTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "operations": {
                  "type": "array",
                  "description": "Files to copy.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "source_path": {"type": "string"},
                      "target_path": {"type": "string"},
                      "replacements": {"type": "array"},
                      "regex_replacements": {"type": "array"}
                    },
                    "required": ["source_path", "target_path"]
                  }
                },
                "replacements": {"type": "array", "description": "Plain replacements applied to all operations."},
                "regex_replacements": {"type": "array", "description": "Regex replacements applied to all operations."},
                "overwrite": {"type": "boolean", "description": "Allow replacing existing targets."},
                "create_dirs": {"type": "boolean", "description": "Create missing target parent folders."},
                "encoding": {"type": "string", "description": "Text encoding, defaults to UTF-8."},
                "preserve_eol": {"type": "boolean", "description": "Preserve source line separator in replacement text."},
                "refresh_workspace": {"type": "boolean", "description": "Refresh target projects after writing."},
                "dry_run": {"type": "boolean", "description": "Report changes without writing."}
              },
              "required": ["operations"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Copy multiple workspace text files with shared replacements and per-file results."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return false;
    }

    @Override
    public boolean isDestructive() {
        return false;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BatchResult result = executeBatch(params.getRaw());
                String json = GSON.toJson(result.payload());
                return result.success()
                        ? ToolResult.success(json, ToolResult.ToolResultType.CODE, result.payload())
                        : ToolResult.failure(json, result.payload());
            } catch (Exception e) {
                JsonObject error = WorkspaceCopyTransformTool.errorPayload("BATCH_COPY_TRANSFORM_ERROR", e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(GSON.toJson(error), error);
            }
        });
    }

    private BatchResult executeBatch(Map<String, Object> raw) {
        String batchOpId = "workspace-copy-transform-batch-" + UUID.randomUUID(); //$NON-NLS-1$
        List<Map<String, Object>> operations = WorkspaceCopyTransformSupport.parseObjectList(raw.get("operations"), //$NON-NLS-1$
                "operations"); //$NON-NLS-1$
        LOG.info("[%s] copy_transform_batch operations=%d", batchOpId, operations.size()); //$NON-NLS-1$
        if (operations.isEmpty()) {
            JsonObject error = WorkspaceCopyTransformTool.errorPayload("OPERATIONS_REQUIRED", //$NON-NLS-1$
                    "operations must contain at least one item"); //$NON-NLS-1$
            WorkspaceCopyTransformTool.addOpId(error, batchOpId);
            return new BatchResult(false, error);
        }

        WorkspaceCopyTransformTool.OperationDefaults defaults = defaultsFrom(raw);
        JsonArray results = new JsonArray();
        boolean dryRun = booleanOrDefault(raw.get("dry_run"), false); //$NON-NLS-1$
        int done = 0;
        int failed = 0;
        int skipped = 0;
        boolean stopped = false;

        for (int i = 0; i < operations.size(); i++) {
            Map<String, Object> operation = operations.get(i);
            if (stopped) {
                results.add(skippedPayload(i, operation));
                skipped++;
                continue;
            }

            WorkspaceCopyTransformTool.OperationRequest request =
                    WorkspaceCopyTransformTool.requestFrom(operation, defaults);
            if (dryRun && !request.dryRun()) {
                request = new WorkspaceCopyTransformTool.OperationRequest(request.sourcePath(), request.targetPath(),
                        request.overwrite(), request.createDirs(), request.replacements(),
                        request.regexReplacements(), request.charset(), request.preserveEol(),
                        request.refreshWorkspace(), true);
            }
            WorkspaceCopyTransformTool.OperationOutcome outcome =
                    WorkspaceCopyTransformTool.executeOperation(request);
            JsonObject opPayload = outcome.payload().deepCopy();
            opPayload.addProperty("index", i); //$NON-NLS-1$
            results.add(opPayload);

            if (outcome.success()) {
                if (!request.dryRun()) {
                    done++;
                }
            } else {
                failed++;
                if (!dryRun) {
                    stopped = true;
                }
            }
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("status", resolveStatus(dryRun, failed, done)); //$NON-NLS-1$
        payload.addProperty("op_id", batchOpId); //$NON-NLS-1$
        payload.addProperty("total", operations.size()); //$NON-NLS-1$
        payload.addProperty("done", done); //$NON-NLS-1$
        payload.addProperty("failed", failed); //$NON-NLS-1$
        payload.addProperty("skipped", skipped); //$NON-NLS-1$
        payload.add("operations", results); //$NON-NLS-1$
        boolean success = dryRun || failed == 0;
        return new BatchResult(success, payload);
    }

    private static WorkspaceCopyTransformTool.OperationDefaults defaultsFrom(Map<String, Object> raw) {
        boolean overwrite = booleanOrDefault(raw.get("overwrite"), false); //$NON-NLS-1$
        boolean createDirs = booleanOrDefault(raw.get("create_dirs"), false); //$NON-NLS-1$
        boolean preserveEol = booleanOrDefault(raw.get("preserve_eol"), true); //$NON-NLS-1$
        boolean refreshWorkspace = booleanOrDefault(raw.get("refresh_workspace"), true); //$NON-NLS-1$
        boolean dryRun = booleanOrDefault(raw.get("dry_run"), false); //$NON-NLS-1$
        String encoding = WorkspaceCopyTransformSupport.stringValue(raw.get("encoding")); //$NON-NLS-1$
        if (encoding == null) {
            encoding = "UTF-8"; //$NON-NLS-1$
        }
        return new WorkspaceCopyTransformTool.OperationDefaults(null, null, overwrite, createDirs,
                new ArrayList<>(WorkspaceCopyTransformSupport.parsePlainReplacements(raw.get("replacements"))), //$NON-NLS-1$
                new ArrayList<>(WorkspaceCopyTransformSupport.parseRegexReplacements(raw.get("regex_replacements"))), //$NON-NLS-1$
                encoding, preserveEol, refreshWorkspace, dryRun);
    }

    private static JsonObject skippedPayload(int index, Map<String, Object> operation) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "skipped"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("index", index); //$NON-NLS-1$
        payload.addProperty("source_path", WorkspaceCopyTransformSupport.stringValue(operation.get("source_path"))); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("target_path", WorkspaceCopyTransformSupport.stringValue(operation.get("target_path"))); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("message", "Skipped because a previous operation failed"); //$NON-NLS-1$ //$NON-NLS-2$
        return payload;
    }

    private static String resolveStatus(boolean dryRun, int failed, int done) {
        if (dryRun) {
            return "dry_run"; //$NON-NLS-1$
        }
        if (failed == 0) {
            return "ok"; //$NON-NLS-1$
        }
        return done > 0 ? "partial" : "failed"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean booleanOrDefault(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private record BatchResult(boolean success, JsonObject payload) {
    }
}
