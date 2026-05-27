/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;

import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionManager;

/**
 * Инструмент записи файлов workspace.
 *
 * <p>Используется для изменения существующих файлов в workspace.
 * Дополнительно разрешает создать Code.md в корне текущего проекта.</p>
 *
 * <p>Особенности:</p>
 * <ul>
 *   <li>Не создает новые файлы и директории, кроме Code.md в корне проекта</li>
 *   <li>Поддерживает UTF-8 кодировку</li>
 *   <li>Работает только в пределах workspace (безопасность)</li>
 *   <li>Перезаписывает существующие файлы (с overwrite=true)</li>
 * </ul>
 */
@ToolMeta(name = "write_file", category = "file", mutating = true, tags = {"workspace"})
public class WriteTool extends AbstractTool {

    private static final String PLUGIN_ID = "com.codepilot1c.core";

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "Path to a workspace file. Existing files are overwritten; project-root Code.md may be created."
                    },
                    "content": {
                        "type": "string",
                        "description": "Full new file content"
                    },
                    "overwrite": {
                        "type": "boolean",
                        "description": "Must be true; existing files are overwritten and project-root Code.md may be created"
                    }
                },
                "required": ["path", "content"]
            }
            """;

    @Override
    public String getDescription() {
        return "Перезаписывает файл workspace целиком; может создать Code.md в корне текущего проекта. Используй, когда нужен осознанный full overwrite без patch-логики. Предпочитай edit_file для точечных правок и не используй этот tool как основной путь изменения EDT metadata или .mdo файлов."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return false;  // Агент может создавать файлы без подтверждения
    }

    @Override
    public boolean isDestructive() {
        return false;  // Не требует специальной обработки
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        Map<String, Object> parameters = params.getRaw();
        return CompletableFuture.supplyAsync(() -> {
            String pathStr = (String) parameters.get("path");
            if (pathStr == null || pathStr.isEmpty()) {
                return ToolResult.failure("Параметр path обязателен");
            }

            String content = (String) parameters.get("content");
            if (content == null) {
                content = "";
            }

            boolean overwrite = Boolean.TRUE.equals(parameters.get("overwrite"));

            if (!overwrite) {
                return ToolResult.failure(
                        "write_file разрешен только для перезаписи существующих файлов. " +
                        "Укажите overwrite=true.");
            }

            try {
                return writeFile(pathStr, content);
            } catch (CoreException e) {
                logError("Ошибка создания файла", e);
                return ToolResult.failure("Ошибка записи файла: " + e.getMessage());
            }
        });
    }

    /**
     * Записывает содержимое в файл workspace.
     */
    private ToolResult writeFile(String pathStr, String content)
            throws CoreException {

        // Normalize path
        String normalizedPath = normalizePath(pathStr);

        // КРИТИЧНО: Любое прямое редактирование metadata descriptor (.mdo) запрещено.
        if (isMetadataDescriptorPath(normalizedPath)) {
            logWarning("═══════════════════════════════════════════════════════════════");
            logWarning("[WRITE_FILE] ✗ ЗАБЛОКИРОВАНО: Попытка редактировать .mdo файл напрямую!");
            logWarning("[WRITE_FILE] Путь: " + normalizedPath);
            logWarning("[WRITE_FILE] Размер контента: " + (content != null ? content.length() : 0) + " символов");
            logWarning("[WRITE_FILE] РЕШЕНИЕ: Используйте create_metadata/create_form/add_metadata_child для изменения метаданных");
            logWarning("═══════════════════════════════════════════════════════════════");
            return ToolResult.failure(
                    "❌ ОШИБКА: Нельзя редактировать .mdo файлы метаданных напрямую через write_file!\n\n" +
                    "Используйте инструмент **create_metadata** для создания top-level объектов метаданных.\n" +
                    "Для форм используйте **create_form**.\n" +
                    "Для табличных частей и реквизитов табличных частей используйте **add_metadata_child**.\n" +
                    "Это необходимо, чтобы изменения проходили через штатный BM API EDT.\n\n" +
                    "Пример: create_metadata(kind=\"Catalog\", name=\"Контрагенты\", synonym=\"Контрагенты\")");
        }

        // FORM/DCS/TEMPLATE artifacts are structured EDT files and must be changed through semantic tools.
        if (isStructuredEdtArtifactPath(normalizedPath)) {
            logWarning("═══════════════════════════════════════════════════════════════");
            logWarning("[WRITE_FILE] ✗ ЗАБЛОКИРОВАНО: Попытка записать структурный EDT artifact напрямую!");
            logWarning("[WRITE_FILE] Путь: " + normalizedPath);
            logWarning("[WRITE_FILE] Размер контента: " + (content != null ? content.length() : 0) + " символов");
            logWarning("[WRITE_FILE] РЕШЕНИЕ: Используйте create_form/mutate_form_model, dcs_manage или render_template");
            logWarning("═══════════════════════════════════════════════════════════════");
            return ToolResult.failure(
                    "Writing structured EDT artifacts is blocked. Use semantic EDT tools instead.\n\n" +
                    "Для форм используйте create_form/apply_form_recipe/mutate_form_model.\n" +
                    "Для СКД используйте dcs_manage.\n" +
                    "Для .mxl макетов используйте add_metadata_child(child_kind=Template) и render_template.");
        }

        // Get workspace
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Find file handle
        IFile file = findOrCreateFile(root, normalizedPath);
        if (file == null) {
            return ToolResult.failure("Не удалось получить файл: " + pathStr);
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        boolean created = false;

        if (!file.exists() && !isProjectRootCodeMd(file)) {
            return ToolResult.failure(
                    "Создание новых файлов через write_file запрещено: " + file.getFullPath() + ". " +
                    "Исключение разрешено только для Code.md в корне проекта. " +
                    "Используйте ensure_module_artifact для подготовки Module.bsl/ObjectModule.bsl/ManagerModule.bsl, " +
                    "после чего применяйте edit_file/write_file только к существующему файлу.");
        }

        if (file.exists()) {
            file.setContents(new ByteArrayInputStream(bytes), IResource.FORCE, new NullProgressMonitor());
        } else {
            file.create(new ByteArrayInputStream(bytes), IResource.FORCE, new NullProgressMonitor());
            created = true;
        }

        // Refresh
        file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

        // Build result
        StringBuilder result = new StringBuilder();
        result.append("**Файл обновлен:** `").append(file.getFullPath()).append("`\n");
        result.append("**Размер:** ").append(bytes.length).append(" байт\n");
        result.append("**Строк:** ").append(countLines(content)).append("\n");
        result.append("**Статус:** ").append(created ? "создан" : "перезаписан");

        logInfo((created ? "Файл создан: " : "Файл обновлен: ") + file.getFullPath());

        return ToolResult.success(result.toString(), ToolResult.ToolResultType.TEXT);
    }

    /**
     * Нормализует путь.
     */
    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }

        // Remove leading slash
        String normalized = path;
        if (normalized.startsWith("/") && !normalized.startsWith("//")) {
            normalized = normalized.substring(1);
        }

        // Convert separators
        normalized = normalized.replace('\\', '/');

        return ProjectMemoryFilePolicy.canonicalizeBarePath(normalized);
    }

    /**
     * Находит или создает файл по пути.
     */
    private IFile findOrCreateFile(IWorkspaceRoot root, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        try {
            IPath ipath = org.eclipse.core.runtime.Path.fromPortableString(path);
            if (ipath.segmentCount() == 1) {
                IProject project = resolveCurrentProject(root);
                return project != null ? project.getFile(ipath) : null;
            }
            return root.getFile(ipath);
        } catch (Exception e) {
            logError("Ошибка получения файла: " + path, e);
            return null;
        }
    }

    private IProject resolveCurrentProject(IWorkspaceRoot root) {
        try {
            Session session = SessionManager.getInstance().getOrCreateCurrentSession();
            if (session != null && session.getProjectPath() != null && !session.getProjectPath().isEmpty()) {
                IProject project = SessionManager.getInstance().findProjectByPath(session.getProjectPath());
                if (project != null && project.exists() && project.isOpen()) {
                    return project;
                }
            }
        } catch (Exception e) {
            logWarning("Не удалось определить текущий проект из сессии: " + e.getMessage());
        }

        for (IProject project : root.getProjects()) {
            if (project.exists() && project.isOpen()) {
                return project;
            }
        }
        return null;
    }

    private boolean isProjectRootCodeMd(IFile file) {
        if (file == null || file.getProjectRelativePath() == null) {
            return false;
        }
        if (file.getProjectRelativePath().segmentCount() != 1) {
            return false;
        }
        return ProjectMemoryFilePolicy.isCanonicalFileName(file.getName());
    }

    /**
     * Подсчитывает количество строк.
     */
    private int countLines(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return content.split("\r\n|\r|\n", -1).length;
    }

    private boolean isMetadataDescriptorPath(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mdo"); //$NON-NLS-1$
    }

    static boolean isStructuredEdtArtifactPath(String normalizedPath) {
        if (normalizedPath == null) {
            return false;
        }
        String lower = normalizedPath.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".form") //$NON-NLS-1$
                || lower.endsWith(".form.xml") //$NON-NLS-1$
                || lower.endsWith(".mxl") //$NON-NLS-1$
                || lower.endsWith("/main@datacompositionschema.xml") //$NON-NLS-1$
                || lower.endsWith("/maindatacompositionschema.xml") //$NON-NLS-1$
                || lower.contains("/ext/maindatacompositionschema."); //$NON-NLS-1$
    }

    private void logInfo(String message) {
        log.log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    private void logWarning(String message) {
        log.log(new Status(IStatus.WARNING, PLUGIN_ID, message));
    }

    private void logError(String message, Throwable error) {
        log.log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
    }
}
