/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;

import com.codepilot1c.core.logging.LogSanitizer;
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
 * Copies one workspace text file to another path with optional replacements.
 */
@ToolMeta(
    name = "workspace_copy_transform",
    category = "file",
    mutating = true,
    tags = {"workspace"}
)
public class WorkspaceCopyTransformTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(WorkspaceCopyTransformTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "source_path": {"type": "string", "description": "Workspace-relative source file path."},
                "target_path": {"type": "string", "description": "Workspace-relative target file path."},
                "overwrite": {"type": "boolean", "description": "Allow replacing an existing target file."},
                "create_dirs": {"type": "boolean", "description": "Create missing target parent folders."},
                "replacements": {
                  "type": "array",
                  "description": "Plain text replacements.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "from": {"type": "string"},
                      "to": {"type": "string"}
                    },
                    "required": ["from", "to"]
                  }
                },
                "regex_replacements": {
                  "type": "array",
                  "description": "Regex replacements using Java Pattern syntax.",
                  "items": {
                    "type": "object",
                    "properties": {
                      "pattern": {"type": "string"},
                      "replacement": {"type": "string"}
                    },
                    "required": ["pattern", "replacement"]
                  }
                },
                "encoding": {"type": "string", "description": "Text encoding, defaults to UTF-8."},
                "preserve_eol": {"type": "boolean", "description": "Preserve source line separator in inserted replacement text."},
                "refresh_workspace": {"type": "boolean", "description": "Refresh target project after writing."},
                "dry_run": {"type": "boolean", "description": "Report changes without writing."}
              },
              "required": ["source_path", "target_path"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Copy a workspace text file with replacements, safety checks, dry-run, and refresh."; //$NON-NLS-1$
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
                OperationRequest request = requestFrom(params.getRaw(), null);
                OperationOutcome outcome = executeOperation(request);
                return outcome.toToolResult();
            } catch (Exception e) {
                JsonObject error = errorPayload("INTERNAL_ERROR", e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(GSON.toJson(error), error);
            }
        });
    }

    static OperationRequest requestFrom(Map<String, Object> raw, OperationDefaults defaults) {
        OperationDefaults effective = defaults != null ? defaults : OperationDefaults.empty();
        String sourcePath = stringOrDefault(raw.get("source_path"), effective.sourcePath()); //$NON-NLS-1$
        String targetPath = stringOrDefault(raw.get("target_path"), effective.targetPath()); //$NON-NLS-1$
        boolean overwrite = booleanOrDefault(raw.get("overwrite"), effective.overwrite()); //$NON-NLS-1$
        boolean createDirs = booleanOrDefault(raw.get("create_dirs"), effective.createDirs()); //$NON-NLS-1$
        boolean preserveEol = booleanOrDefault(raw.get("preserve_eol"), effective.preserveEol()); //$NON-NLS-1$
        boolean refreshWorkspace = booleanOrDefault(raw.get("refresh_workspace"), effective.refreshWorkspace()); //$NON-NLS-1$
        boolean dryRun = booleanOrDefault(raw.get("dry_run"), effective.dryRun()); //$NON-NLS-1$
        String encoding = stringOrDefault(raw.get("encoding"), effective.encoding()); //$NON-NLS-1$

        List<WorkspaceCopyTransformSupport.PlainReplacement> plain = new ArrayList<>(effective.replacements());
        plain.addAll(WorkspaceCopyTransformSupport.parsePlainReplacements(raw.get("replacements"))); //$NON-NLS-1$
        List<WorkspaceCopyTransformSupport.RegexReplacement> regex = new ArrayList<>(effective.regexReplacements());
        regex.addAll(WorkspaceCopyTransformSupport.parseRegexReplacements(raw.get("regex_replacements"))); //$NON-NLS-1$

        return new OperationRequest(sourcePath, targetPath, overwrite, createDirs, plain, regex,
                WorkspaceCopyTransformSupport.resolveCharset(encoding), preserveEol, refreshWorkspace, dryRun);
    }

    static OperationOutcome executeOperation(OperationRequest request) {
        String opId = "workspace-copy-transform-" + UUID.randomUUID(); //$NON-NLS-1$
        LOG.info("[%s] copy_transform source=%s target=%s dryRun=%b", opId, //$NON-NLS-1$
                LogSanitizer.truncatePath(request.sourcePath()),
                LogSanitizer.truncatePath(request.targetPath()),
                request.dryRun());

        WorkspaceCopyTransformSupport.Validation sourceValidation =
                WorkspaceCopyTransformSupport.validateWorkspacePath(request.sourcePath(), false);
        WorkspaceCopyTransformSupport.Validation targetValidation =
                WorkspaceCopyTransformSupport.validateWorkspacePath(request.targetPath(), true);
        if (!sourceValidation.ok()) {
            JsonObject payload = errorPayload(sourceValidation.errorCode(), sourceValidation.message());
            addOpId(payload, opId);
            return OperationOutcome.failure(payload);
        }
        if (!targetValidation.ok()) {
            JsonObject payload = errorPayload(targetValidation.errorCode(), targetValidation.message());
            addOpId(payload, opId);
            return OperationOutcome.failure(payload);
        }

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IFile source = root.getFile(Path.fromPortableString(sourceValidation.normalizedPath()));
        IFile target = root.getFile(Path.fromPortableString(targetValidation.normalizedPath()));

        try {
            return executeResolved(request, sourceValidation.normalizedPath(), targetValidation.normalizedPath(),
                    source, target, opId);
        } catch (CoreException | IOException | RuntimeException e) {
            LOG.error("[%s] copy_transform failed: %s", opId, e.getMessage()); //$NON-NLS-1$
            JsonObject payload = errorPayload("COPY_TRANSFORM_ERROR", e.getMessage()); //$NON-NLS-1$
            addOpId(payload, opId);
            return OperationOutcome.failure(payload);
        }
    }

    private static OperationOutcome executeResolved(OperationRequest request, String sourcePath, String targetPath,
            IFile source, IFile target, String opId) throws CoreException, IOException {
        boolean sourceExists = source.exists();
        boolean targetExists = target.exists();
        List<String> problems = new ArrayList<>();
        if (!sourceExists) {
            problems.add("Source file not found: " + sourcePath); //$NON-NLS-1$
        }
        if (targetExists && !request.overwrite()) {
            problems.add("Target exists and overwrite is not true: " + targetPath); //$NON-NLS-1$
        }
        if (!targetExists && !request.createDirs() && !target.getParent().exists()) {
            problems.add("Target parent folder does not exist and create_dirs is not true: " + targetPath); //$NON-NLS-1$
        }

        byte[] sourceBytes = sourceExists ? readAll(source) : new byte[0];
        String sourceText = new String(sourceBytes, request.charset());
        WorkspaceCopyTransformSupport.TransformResult transform = WorkspaceCopyTransformSupport.transform(sourceText,
                request.replacements(), request.regexReplacements(), request.preserveEol());
        byte[] targetBytes = transform.content().getBytes(request.charset());

        if (request.dryRun()) {
            return OperationOutcome.success(buildDryRunPayload(sourcePath, targetPath, sourceExists, targetExists,
                    sourceBytes.length, transform, problems, opId));
        }
        if (!problems.isEmpty()) {
            JsonObject payload = errorPayload("COPY_TRANSFORM_PRECHECK_FAILED", String.join("; ", problems)); //$NON-NLS-1$ //$NON-NLS-2$
            addOpId(payload, opId);
            payload.add("problems", problemsJson(problems)); //$NON-NLS-1$
            payload.addProperty("source_path", sourcePath); //$NON-NLS-1$
            payload.addProperty("target_path", targetPath); //$NON-NLS-1$
            return OperationOutcome.failure(payload);
        }

        if (!target.exists()) {
            ensureParentExists(target);
            target.create(new ByteArrayInputStream(targetBytes), IResource.FORCE, new NullProgressMonitor());
        } else {
            target.setContents(new ByteArrayInputStream(targetBytes), IResource.FORCE | IResource.KEEP_HISTORY,
                    new NullProgressMonitor());
        }
        boolean refreshed = refreshTarget(target, request.refreshWorkspace());

        JsonObject payload = new JsonObject();
        payload.addProperty("status", "ok"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("op_id", opId); //$NON-NLS-1$
        payload.addProperty("source_path", sourcePath); //$NON-NLS-1$
        payload.addProperty("target_path", targetPath); //$NON-NLS-1$
        payload.addProperty("bytes_written", targetBytes.length); //$NON-NLS-1$
        payload.addProperty("sha256_before", WorkspaceCopyTransformSupport.sha256(sourceBytes)); //$NON-NLS-1$
        payload.addProperty("sha256_after", WorkspaceCopyTransformSupport.sha256(targetBytes)); //$NON-NLS-1$
        payload.addProperty("workspace_refreshed", refreshed); //$NON-NLS-1$
        payload.add("replacement_counts", //$NON-NLS-1$
                WorkspaceCopyTransformSupport.replacementCountsJson(transform.replacementCounts()));
        return OperationOutcome.success(payload);
    }

    private static JsonObject buildDryRunPayload(String sourcePath, String targetPath, boolean sourceExists,
            boolean targetExists, int sourceBytes, WorkspaceCopyTransformSupport.TransformResult transform,
            List<String> problems, String opId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "dry_run"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("op_id", opId); //$NON-NLS-1$
        payload.addProperty("source_path", sourcePath); //$NON-NLS-1$
        payload.addProperty("target_path", targetPath); //$NON-NLS-1$
        payload.addProperty("source_exists", sourceExists); //$NON-NLS-1$
        payload.addProperty("target_exists", targetExists); //$NON-NLS-1$
        payload.addProperty("source_bytes", sourceBytes); //$NON-NLS-1$
        payload.addProperty("would_write", problems.isEmpty()); //$NON-NLS-1$
        payload.add("replacement_counts", //$NON-NLS-1$
                WorkspaceCopyTransformSupport.replacementCountsJson(transform.replacementCounts()));
        payload.add("changed_lines", WorkspaceCopyTransformSupport.changedLinesJson(transform.changedLines())); //$NON-NLS-1$
        payload.add("problems", problemsJson(problems)); //$NON-NLS-1$
        return payload;
    }

    private static JsonArray problemsJson(List<String> problems) {
        JsonArray array = new JsonArray();
        for (String problem : problems) {
            array.add(problem);
        }
        return array;
    }

    private static byte[] readAll(IFile file) throws CoreException, IOException {
        try (InputStream stream = file.getContents(true);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[8192];
            int read;
            while ((read = stream.read(data)) != -1) {
                buffer.write(data, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private static void ensureParentExists(IFile file) throws CoreException {
        IContainer parent = file.getParent();
        if (parent instanceof IFolder folder && !folder.exists()) {
            createFolderChain(folder);
        }
    }

    private static void createFolderChain(IFolder folder) throws CoreException {
        IContainer parent = folder.getParent();
        if (parent instanceof IFolder parentFolder && !parentFolder.exists()) {
            createFolderChain(parentFolder);
        }
        if (!folder.exists()) {
            folder.create(true, true, new NullProgressMonitor());
        }
    }

    private static boolean refreshTarget(IFile target, boolean requested) throws CoreException {
        if (!requested) {
            return false;
        }
        target.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
        if (target.getProject() != null) {
            target.getProject().refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
        }
        return true;
    }

    static JsonObject errorPayload(String code, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("error_code", code != null ? code : "ERROR"); //$NON-NLS-1$ //$NON-NLS-2$
        payload.addProperty("message", message != null ? message : "Unknown error"); //$NON-NLS-1$ //$NON-NLS-2$
        return payload;
    }

    static void addOpId(JsonObject payload, String opId) {
        if (payload != null && opId != null && !opId.isBlank()) {
            payload.addProperty("op_id", opId); //$NON-NLS-1$
        }
    }

    private static String stringOrDefault(Object value, String defaultValue) {
        String str = WorkspaceCopyTransformSupport.stringValue(value);
        return str != null ? str : defaultValue;
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

    record OperationRequest(String sourcePath, String targetPath, boolean overwrite, boolean createDirs,
            List<WorkspaceCopyTransformSupport.PlainReplacement> replacements,
            List<WorkspaceCopyTransformSupport.RegexReplacement> regexReplacements,
            Charset charset, boolean preserveEol, boolean refreshWorkspace, boolean dryRun) {
    }

    record OperationDefaults(String sourcePath, String targetPath, boolean overwrite, boolean createDirs,
            List<WorkspaceCopyTransformSupport.PlainReplacement> replacements,
            List<WorkspaceCopyTransformSupport.RegexReplacement> regexReplacements,
            String encoding, boolean preserveEol, boolean refreshWorkspace, boolean dryRun) {
        static OperationDefaults empty() {
            return new OperationDefaults(null, null, false, false, List.of(), List.of(),
                    "UTF-8", true, true, false); //$NON-NLS-1$
        }
    }

    record OperationOutcome(boolean success, JsonObject payload) {
        static OperationOutcome success(JsonObject payload) {
            return new OperationOutcome(true, payload);
        }

        static OperationOutcome failure(JsonObject payload) {
            return new OperationOutcome(false, payload);
        }

        ToolResult toToolResult() {
            String json = GSON.toJson(payload);
            return success
                    ? ToolResult.success(json, ToolResult.ToolResultType.CODE, payload)
                    : ToolResult.failure(json, payload);
        }
    }
}
