/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.HomePageWorkAreaRequest;
import com.codepilot1c.core.edt.metadata.HomePageWorkAreaResult;
import com.codepilot1c.core.edt.metadata.HomePageWorkAreaXml;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.SupportLockGuard;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Рабочая область начальной страницы конфигурации: шаблон и формы по колонкам.
 *
 * <p>Единственный маршрут для {@code src/Configuration/HomePageWorkArea.hpwa}: формы
 * сверяются с моделью проекта (BM) до записи, файл пишется в формате EDT и синхронизируется
 * с BM. Без этого тула файл можно было положить только руками или через EDT UI, потому что
 * {@code write_file} по замыслу не создаёт структурные артефакты.</p>
 */
@ToolMeta(name = "set_home_page_work_area", category = "metadata", mutating = true, tags = {"workspace", "edt"})
public class SetHomePageWorkAreaTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(SetHomePageWorkAreaTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "EDT project name (configuration project)"
                },
                "template": {
                  "type": "string",
                  "description": "Working area template: OneColumn, TwoColumnsEqualWidth (default) or TwoColumnsVariableWidth"
                },
                "left": {
                  "type": "array",
                  "description": "Forms of the left (or the only) column, top to bottom",
                  "items": {
                    "type": "object",
                    "properties": {
                      "form": { "type": "string", "description": "Form FQN: <Kind>.<Name>.Form.<FormName> or CommonForm.<Name>" },
                      "height": { "type": "integer", "description": "Height in rows, default 10" },
                      "visible": { "type": "boolean", "description": "Visible by default, default true" }
                    },
                    "required": ["form"]
                  }
                },
                "right": {
                  "type": "array",
                  "description": "Forms of the right column (two-column templates only)",
                  "items": {
                    "type": "object",
                    "properties": {
                      "form": { "type": "string" },
                      "height": { "type": "integer" },
                      "visible": { "type": "boolean" }
                    },
                    "required": ["form"]
                  }
                },
                "allow_supported_object_edit": {
                  "type": "boolean",
                  "description": "Явное согласие править конфигурацию, находящуюся на поддержке с замком (антипаттерн #70). По умолчанию false."
                }
              },
              "required": ["project", "left"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;

    public SetHomePageWorkAreaTool() {
        this(new EdtMetadataService());
    }

    SetHomePageWorkAreaTool(EdtMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Override
    public String getDescription() {
        return "Задаёт рабочую область начальной страницы конфигурации (src/Configuration/HomePageWorkArea.hpwa): " //$NON-NLS-1$
                + "шаблон колонок и формы по колонкам. Формы проверяются по модели проекта до записи; " //$NON-NLS-1$
                + "файл пишется в формате EDT и синхронизируется с BM. Существующая область перезаписывается целиком."; //$NON-NLS-1$
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
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("home-page"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START set_home_page_work_area", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, //$NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project", "project_name", "projectName"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (projectName == null || projectName.isBlank()) {
                    return ToolResult.failure("Параметр project обязателен"); //$NON-NLS-1$
                }
                HomePageWorkAreaRequest request = new HomePageWorkAreaRequest(
                        projectName,
                        getString(parameters, "template"), //$NON-NLS-1$
                        parseItems(parameters.get("left"), "left"), //$NON-NLS-1$ //$NON-NLS-2$
                        parseItems(parameters.get("right"), "right"), //$NON-NLS-1$ //$NON-NLS-2$
                        SupportLockGuard.isAllowed(parameters));
                HomePageWorkAreaResult result = metadataService.setHomePageWorkArea(request);
                LOG.info("[%s] SUCCESS in %s path=%s forms=%d", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.path(), Integer.valueOf(result.formsCount()));
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(), e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (IllegalArgumentException e) {
                LOG.warn("[%s] REJECTED: %s", opId, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure("[INVALID_PROPERTY_VALUE] " + e.getMessage()); //$NON-NLS-1$
            } catch (Exception e) {
                LOG.error("[" + opId + "] set_home_page_work_area failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка set_home_page_work_area: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    /** Колонка из JSON-массива объектов {form, height, visible}; отсутствующий массив - пустая колонка. */
    static List<HomePageWorkAreaXml.Item> parseItems(Object raw, String columnName) {
        List<HomePageWorkAreaXml.Item> items = new ArrayList<>();
        if (raw == null) {
            return items;
        }
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("Parameter " + columnName + " must be an array of {form, height, visible}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (Object element : list) {
            if (element instanceof String formFqn) {
                items.add(new HomePageWorkAreaXml.Item(formFqn, HomePageWorkAreaXml.DEFAULT_HEIGHT, true));
                continue;
            }
            if (!(element instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("Column " + columnName + ": each item must be an object {form, height, visible}"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Object form = map.get("form"); //$NON-NLS-1$
            if (form == null) {
                form = map.get("form_fqn"); //$NON-NLS-1$
            }
            if (form == null || String.valueOf(form).isBlank()) {
                throw new IllegalArgumentException("Column " + columnName + ": item without form"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            int height = HomePageWorkAreaXml.DEFAULT_HEIGHT;
            Object rawHeight = map.get("height"); //$NON-NLS-1$
            if (rawHeight instanceof Number number) {
                height = number.intValue();
            } else if (rawHeight != null) {
                height = Integer.parseInt(String.valueOf(rawHeight).trim());
            }
            boolean visible = true;
            Object rawVisible = map.get("visible"); //$NON-NLS-1$
            if (rawVisible instanceof Boolean bool) {
                visible = bool.booleanValue();
            } else if (rawVisible != null) {
                visible = Boolean.parseBoolean(String.valueOf(rawVisible));
            }
            items.add(new HomePageWorkAreaXml.Item(String.valueOf(form).trim(), height, visible));
        }
        return items;
    }

    private static String getString(Map<String, Object> parameters, String... keys) {
        for (String key : keys) {
            Object value = parameters.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
