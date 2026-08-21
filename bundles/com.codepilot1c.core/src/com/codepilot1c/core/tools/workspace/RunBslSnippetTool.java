/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.diagnostics.BslSilentTypeLinter;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Executes an arbitrary BSL snippet INSIDE the 1C platform and returns whatever the snippet
 * put into the {@code Результат} variable.
 *
 * <p>Why not an external data processor: {@code 1cv8 ENTERPRISE /Execute <file.epf>} raises a
 * modal security warning ("Предупреждение безопасности") unless dangerous-action protection is
 * switched off for the infobase user, which is a security setting change. Code that already
 * lives in a LOADED extension raises nothing — that is the channel {@code run_yaxunit_tests}
 * uses, so this tool rides on it.
 *
 * <p>Contract, both halves must be in place:
 * <ul>
 *   <li>the receiver common module ({@code Тесты_КодепилотСниппет} by default) is delivered into
 *       the infobase as part of the test extension. It reads the snippet file, runs it via
 *       {@code Выполнить()} and raises an exception whose text carries the result — that text
 *       reaches us through junit.xml and the runner's {@code violations[].message};</li>
 *   <li>this tool writes the snippet file into the platform's temp directory. The platform's
 *       {@code КаталогВременныхФайлов()} and this JVM's {@code java.io.tmpdir} resolve to the
 *       same directory when EDT and a file infobase run under one user; pass {@code snippet_path}
 *       explicitly when they do not.</li>
 * </ul>
 *
 * <p>The receiver deletes the snippet file right after reading it, so an ordinary full test run
 * never re-executes a stale snippet.
 */
@ToolMeta(
        name = "run_bsl_snippet",
        category = "diagnostics",
        surfaceCategory = "testing",
        mutating = true,
        tags = {"workspace", "bsl", "snippet", "yaxunit"})
public class RunBslSnippetTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(RunBslSnippetTool.class);

    /** Common module in the test extension that actually runs the snippet. */
    static final String DEFAULT_RECEIVER_MODULE = "Тесты_КодепилотСниппет"; //$NON-NLS-1$
    /** File name agreed with the receiver module. */
    static final String SNIPPET_FILE_NAME = "codepilot-snippet.bsl"; //$NON-NLS-1$
    static final String RESULT_PREFIX = "[СНИППЕТ-РЕЗУЛЬТАТ]"; //$NON-NLS-1$
    static final String ERROR_PREFIX = "[СНИППЕТ-ОШИБКА]"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "EDT project name (the BASE project, not an extension)"
                },
                "snippet": {
                  "type": "string",
                  "description": "BSL executed on the server inside the infobase. Put the answer into the Результат variable, e.g. Результат = \\"заказов: \\" + Количество;"
                },
                "receiver_module": {
                  "type": "string",
                  "description": "Common module of the test extension that runs the snippet (default: Тесты_КодепилотСниппет)"
                },
                "snippet_path": {
                  "type": "string",
                  "description": "Explicit snippet file path. Default: <java.io.tmpdir>/codepilot-snippet.bsl, which matches the platform's КаталогВременныхФайлов() for a same-user file infobase."
                },
                "timeout_s": {
                  "type": "integer",
                  "description": "Timeout in seconds (default: 300)"
                }
              },
              "required": ["project_name", "snippet"]
            }
            """; //$NON-NLS-1$

    private final RunYaxunitTestsTool runner;

    public RunBslSnippetTool() {
        this(new RunYaxunitTestsTool());
    }

    RunBslSnippetTool(RunYaxunitTestsTool runner) {
        this.runner = runner;
    }

    @Override
    public String getDescription() {
        return "Executes a BSL snippet inside the infobase and returns the value of Результат."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("bsl-snippet"); //$NON-NLS-1$
            String projectName = params.requireString("project_name"); //$NON-NLS-1$
            String snippet = params.requireString("snippet"); //$NON-NLS-1$
            String receiverModule = params.optString("receiver_module", DEFAULT_RECEIVER_MODULE); //$NON-NLS-1$
            int timeoutSeconds = params.optInt("timeout_s", DEFAULT_TIMEOUT_SECONDS); //$NON-NLS-1$
            Path snippetPath = resolveSnippetPath(params.optString("snippet_path", null)); //$NON-NLS-1$

            if (snippet.isBlank()) {
                return ToolResult.failure("snippet is empty"); //$NON-NLS-1$
            }

            LOG.info("[%s] START run_bsl_snippet project=%s receiver=%s snippet=%s", opId, //$NON-NLS-1$
                    LogSanitizer.truncate(projectName), LogSanitizer.truncate(receiverModule),
                    LogSanitizer.textSize(snippet));

            try {
                Files.createDirectories(snippetPath.getParent());
                Files.writeString(snippetPath, snippet, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return ToolResult.failure("Cannot write snippet file " + snippetPath + ": " + e.getMessage()); //$NON-NLS-1$
            }

            try {
                Map<String, Object> runParams = new LinkedHashMap<>();
                runParams.put("project_name", projectName); //$NON-NLS-1$
                runParams.put("filters", receiverModule); //$NON-NLS-1$
                runParams.put("timeout_s", timeoutSeconds); //$NON-NLS-1$

                ToolResult runResult = runner.execute(runParams).join();
                ToolResult interpreted = interpret(runResult, receiverModule, snippetPath);
                // Antipattern #72 warning: a string literal assigned to a known
                // reference attribute (Роли.Роль) silently becomes an empty reference.
                String lintBlock = BslSilentTypeLinter.formatForResult(
                        null, BslSilentTypeLinter.lint(snippet));
                return lintBlock == null ? interpreted : appendWarningBlock(interpreted, lintBlock);
            } finally {
                // The receiver removes the file itself; this only covers the case where it never ran,
                // so that a later ordinary test run does not pick up a stale snippet.
                try {
                    Files.deleteIfExists(snippetPath);
                } catch (IOException e) {
                    LOG.warn("[%s] Cannot delete snippet file: %s", opId, e.getMessage()); //$NON-NLS-1$
                }
            }
        });
    }

    /** Appends a warning block to the result, preserving outcome, type and structured data. */
    private static ToolResult appendWarningBlock(ToolResult result, String block) {
        if (result.isSuccess()) {
            String combined = result.getContent() + "\n\n" + block; //$NON-NLS-1$
            return result.hasStructuredData()
                    ? ToolResult.success(combined, result.getType(), result.getStructuredData())
                    : ToolResult.success(combined, result.getType());
        }
        String combined = result.getErrorMessage() + "\n\n" + block; //$NON-NLS-1$
        return result.hasStructuredData()
                ? ToolResult.failure(combined, result.getStructuredData())
                : ToolResult.failure(combined);
    }

    private static Path resolveSnippetPath(String explicitPath) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            return Paths.get(explicitPath.trim());
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), SNIPPET_FILE_NAME); //$NON-NLS-1$
    }

    /**
     * Pulls the snippet outcome out of the runner's report. The receiver signals both success and
     * failure through a raised exception, so the payload always arrives as a violation message.
     */
    private static ToolResult interpret(ToolResult runResult, String receiverModule, Path snippetPath) {
        JsonObject structured = runResult.getStructuredData();
        String carried = structured == null ? null : findCarriedMessage(structured);

        if (carried == null) {
            StringBuilder hint = new StringBuilder();
            hint.append("Snippet did not run: no ").append(RESULT_PREFIX).append('/').append(ERROR_PREFIX)
                    .append(" message came back from module ").append(receiverModule).append(". "); //$NON-NLS-1$
            hint.append("Check that the receiver module is delivered into the infobase and that the platform reads ") //$NON-NLS-1$
                    .append(snippetPath).append(" as its КаталогВременныхФайлов()."); //$NON-NLS-1$
            JsonObject payload = structured == null ? new JsonObject() : structured;
            return ToolResult.failure(hint.toString(), payload);
        }

        boolean failed = carried.startsWith(ERROR_PREFIX);
        String body = carried.substring(carried.indexOf(']') + 1).trim();

        JsonObject payload = new JsonObject();
        payload.addProperty("receiver_module", receiverModule); //$NON-NLS-1$
        payload.addProperty("snippet_path", snippetPath.toString()); //$NON-NLS-1$
        payload.addProperty("result", body); //$NON-NLS-1$
        payload.addProperty("failed", failed); //$NON-NLS-1$

        return failed ? ToolResult.failure(body, payload) : ToolResult.success(body, payload);
    }

    private static String findCarriedMessage(JsonObject structured) {
        JsonElement violations = structured.get("violations"); //$NON-NLS-1$
        if (violations == null || !violations.isJsonArray()) {
            return null;
        }
        for (JsonElement element : violations.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            String message = asText(element.getAsJsonObject().get("message")); //$NON-NLS-1$
            if (message == null) {
                continue;
            }
            int result = message.indexOf(RESULT_PREFIX);
            if (result >= 0) {
                return message.substring(result);
            }
            int error = message.indexOf(ERROR_PREFIX);
            if (error >= 0) {
                return message.substring(error);
            }
        }
        return null;
    }

    private static String asText(JsonElement element) {
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
