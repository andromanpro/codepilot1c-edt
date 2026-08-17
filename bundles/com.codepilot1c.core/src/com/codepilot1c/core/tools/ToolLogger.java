/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Детальное логирование вызовов инструментов для отладки агентов.
 *
 * <p>Записывает в файл и консоль:</p>
 * <ul>
 *   <li>Имя инструмента и аргументы</li>
 *   <li>Время начала и окончания</li>
 *   <li>Результат выполнения (успех/ошибка)</li>
 *   <li>Размер результата</li>
 *   <li>Контекст агента (шаг, сессия)</li>
 * </ul>
 *
 * <p>Лог файл: ~/.vibe/tool_calls.log</p>
 */
public class ToolLogger {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolLogger.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ToolLogger instance;

    private final File logFile;
    private final AtomicInteger callCounter = new AtomicInteger(0);
    private final AtomicLong totalExecutionTime = new AtomicLong(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);

    private boolean enabled = true;
    private boolean verboseMode = true;
    private String currentSessionId;
    private int currentAgentStep;

    private ToolLogger() {
        // Create log directory
        String userHome = System.getProperty("user.home");
        File logDir = new File(userHome, ".vibe");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        this.logFile = new File(logDir, "tool_calls.log");
        LOG.info("ToolLogger initialized, log file: %s", logFile.getAbsolutePath());
    }

    /**
     * Возвращает singleton экземпляр.
     */
    public static synchronized ToolLogger getInstance() {
        if (instance == null) {
            instance = new ToolLogger();
        }
        return instance;
    }

    /**
     * Устанавливает контекст текущей сессии агента.
     *
     * @param sessionId ID сессии
     * @param step текущий шаг агента
     */
    public void setAgentContext(String sessionId, int step) {
        this.currentSessionId = sessionId;
        this.currentAgentStep = step;
    }

    /**
     * Логирует начало вызова инструмента.
     *
     * @param toolName имя инструмента
     * @param arguments аргументы вызова
     * @return ID вызова для сопоставления с результатом
     */
    public int logToolCallStart(String toolName, Map<String, Object> arguments) {
        if (!enabled) return -1;

        int callId = callCounter.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════════════════════════\n");
        sb.append(String.format("║ TOOL CALL #%d - %s\n", callId, toolName));
        sb.append(String.format("║ Time: %s\n", now.format(TIME_FORMAT)));

        if (currentSessionId != null) {
            sb.append(String.format("║ Session: %s | Step: %d\n", currentSessionId, currentAgentStep));
        }

        sb.append("╠══════════════════════════════════════════════════════════════════════════════\n");
        sb.append("║ ARGUMENTS:\n");

        if (arguments != null && !arguments.isEmpty()) {
            if (verboseMode) {
                // Подробный вывод аргументов
                for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    String valueStr = formatArgumentValue(value);
                    sb.append(String.format("║   %s: %s\n", key, valueStr));
                }
            } else {
                // Краткий вывод
                sb.append(String.format("║   %s\n", summarizeArguments(arguments)));
            }
        } else {
            sb.append("║   (no arguments)\n");
        }

        sb.append("╠══════════════════════════════════════════════════════════════════════════════\n");
        sb.append("║ EXECUTING...\n");

        String logEntry = sb.toString();
        writeToLog(logEntry);
        LOG.debug("[TOOL #%d] START %s with %d args", callId, toolName,
                arguments != null ? arguments.size() : 0);

        return callId;
    }

    /**
     * Логирует результат выполнения инструмента.
     *
     * @param callId ID вызова
     * @param toolName имя инструмента
     * @param result результат выполнения
     * @param executionTimeMs время выполнения в мс
     */
    public void logToolCallResult(int callId, String toolName, ToolResult result, long executionTimeMs) {
        logToolCallResult(callId, toolName, result, executionTimeMs, false);
    }

    /**
     * Logs a tool result, omitting result content and error text for sensitive tools.
     *
     * @param callId ID вызова
     * @param toolName имя инструмента
     * @param result результат выполнения
     * @param executionTimeMs время выполнения в мс
     * @param sensitive whether result content must be omitted
     */
    public void logToolCallResult(int callId, String toolName, ToolResult result, long executionTimeMs,
            boolean sensitive) {
        if (!enabled) return;

        totalExecutionTime.addAndGet(executionTimeMs);
        if (result.isSuccess()) {
            successCount.incrementAndGet();
        } else {
            failureCount.incrementAndGet();
        }
        writeToLog(formatResultEntry(toolName, result, executionTimeMs, sensitive, verboseMode));

        if (result.isSuccess()) {
            LOG.debug("[TOOL #%d] END %s: SUCCESS in %d ms, %d chars",
                    callId, toolName, executionTimeMs,
                    result.getContent() != null ? result.getContent().length() : 0);
        } else if (sensitive) {
            LOG.warn("[TOOL #%d] END %s: FAILED in %d ms: [omitted: sensitive tool]",
                    callId, toolName, executionTimeMs);
        } else {
            LOG.warn("[TOOL #%d] END %s: FAILED in %d ms: %s",
                    callId, toolName, executionTimeMs, result.getErrorMessage());
        }
    }

    static String formatResultEntry(String toolName, ToolResult result, long executionTimeMs,
            boolean sensitive) {
        return formatResultEntry(toolName, result, executionTimeMs, sensitive, true);
    }

    private static String formatResultEntry(String toolName, ToolResult result, long executionTimeMs,
            boolean sensitive, boolean verbose) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.isSuccess() ? "║ RESULT: SUCCESS\n" : "║ RESULT: FAILURE\n");
        sb.append(String.format("║ Tool: %s\n", toolName));
        sb.append(String.format("║ Result type: %s\n", result.getType().name()));
        sb.append(String.format("║ Execution time: %d ms\n", executionTimeMs));

        String resultText = result.isSuccess() ? result.getContent() : result.getErrorMessage();
        int resultLength = resultText == null ? 0 : resultText.length();
        sb.append(String.format("║ Content length: %d chars\n", resultLength));
        if (sensitive) {
            sb.append("║ Content: [omitted: sensitive tool]\n");
        } else if (result.isSuccess() && resultText != null) {
            if (verbose && resultText.length() <= 2000) {
                sb.append("║ Content:\n");
                sb.append("║ ────────────────────────────────────────────────────────────────────────\n");
                for (String line : resultText.split("\n")) {
                    sb.append("║   ").append(truncate(line, 100)).append("\n");
                }
            } else if (resultText.length() > 2000) {
                sb.append("║ Content (truncated):\n");
                sb.append("║ ────────────────────────────────────────────────────────────────────────\n");
                sb.append("║   ").append(truncate(resultText, 500)).append("...\n");
            }
        } else if (!result.isSuccess()) {
            sb.append(String.format("║ Error: %s\n", resultText));
        }
        sb.append("╚══════════════════════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    /**
     * Логирует ошибку выполнения инструмента.
     *
     * @param callId ID вызова
     * @param toolName имя инструмента
     * @param error исключение
     * @param executionTimeMs время выполнения в мс
     */
    public void logToolCallError(int callId, String toolName, Throwable error, long executionTimeMs) {
        logToolCallError(callId, toolName, error, executionTimeMs, false);
    }

    /**
     * Logs a tool exception without its message or stack trace when the tool is sensitive.
     */
    void logToolCallError(int callId, String toolName, Throwable error, long executionTimeMs,
            boolean sensitive) {
        if (!enabled) return;

        failureCount.incrementAndGet();
        totalExecutionTime.addAndGet(executionTimeMs);

        StringBuilder sb = new StringBuilder();
        sb.append("║ RESULT: EXCEPTION\n");
        sb.append(String.format("║ Execution time: %d ms\n", executionTimeMs));
        sb.append(String.format("║ Exception: %s\n", error.getClass().getSimpleName()));
        if (sensitive) {
            sb.append("║ Message: [omitted: sensitive tool]\n");
        } else {
            sb.append(String.format("║ Message: %s\n", error.getMessage()));
        }

        if (verboseMode && !sensitive) {
            sb.append("║ Stack trace:\n");
            for (StackTraceElement element : error.getStackTrace()) {
                if (element.getClassName().startsWith("com.codepilot1c")) {
                    sb.append(String.format("║   at %s.%s(%s:%d)\n",
                            element.getClassName(), element.getMethodName(),
                            element.getFileName(), element.getLineNumber()));
                }
            }
        }

        sb.append("╚══════════════════════════════════════════════════════════════════════════════\n");

        String logEntry = sb.toString();
        writeToLog(logEntry);

        if (sensitive) {
            LOG.error("[TOOL #%d] END %s: EXCEPTION in %d ms: [omitted: sensitive tool]",
                    callId, toolName, executionTimeMs);
        } else {
            LOG.error("[TOOL #%d] END %s: EXCEPTION in %d ms: %s",
                    callId, toolName, executionTimeMs, error.getMessage());
        }
    }

    /**
     * Возвращает статистику вызовов.
     */
    public String getStatistics() {
        int total = callCounter.get();
        int success = successCount.get();
        int failure = failureCount.get();
        long avgTime = total > 0 ? totalExecutionTime.get() / total : 0;

        return String.format(
                "Tool Call Statistics:\n" +
                "  Total calls: %d\n" +
                "  Successful: %d (%.1f%%)\n" +
                "  Failed: %d (%.1f%%)\n" +
                "  Total execution time: %d ms\n" +
                "  Average execution time: %d ms",
                total,
                success, total > 0 ? (100.0 * success / total) : 0,
                failure, total > 0 ? (100.0 * failure / total) : 0,
                totalExecutionTime.get(),
                avgTime
        );
    }

    /**
     * Сбрасывает статистику.
     */
    public void resetStatistics() {
        callCounter.set(0);
        successCount.set(0);
        failureCount.set(0);
        totalExecutionTime.set(0);
    }

    /**
     * Включает/отключает логирование.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Включает/отключает подробный режим.
     */
    public void setVerboseMode(boolean verbose) {
        this.verboseMode = verbose;
    }

    /**
     * Возвращает путь к файлу лога.
     */
    public String getLogFilePath() {
        return logFile.getAbsolutePath();
    }

    /**
     * Очищает файл лога.
     */
    public void clearLog() {
        try (PrintWriter writer = new PrintWriter(logFile)) {
            writer.print("");
            LOG.info("Tool log cleared");
        } catch (IOException e) {
            LOG.error("Failed to clear tool log: %s", e.getMessage());
        }
    }

    // --- Private methods ---

    private void writeToLog(String entry) {
        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.print(entry);
        } catch (IOException e) {
            LOG.error("Failed to write to tool log: %s", e.getMessage());
        }
    }

    private String formatArgumentValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String strValue) {
            if (strValue.length() > 200) {
                return "\"" + truncate(strValue, 200) + "...\" (" + strValue.length() + " chars)";
            }
            // Show newlines explicitly
            String display = strValue.replace("\n", "\\n").replace("\r", "\\r");
            return "\"" + display + "\"";
        }
        if (value instanceof Map || value instanceof java.util.List) {
            String json = GSON.toJson(value);
            if (json.length() > 200) {
                return truncate(json, 200) + "...";
            }
            return json;
        }
        return String.valueOf(value);
    }

    private String summarizeArguments(Map<String, Object> arguments) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        int i = 0;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (i > 0) sb.append(", ");
            sb.append(entry.getKey()).append("=");
            Object value = entry.getValue();
            if (value instanceof String strValue) {
                sb.append("\"...").append(strValue.length()).append(" chars\"");
            } else {
                sb.append(value);
            }
            i++;
            if (i >= 3 && arguments.size() > 3) {
                sb.append(", ...(").append(arguments.size() - 3).append(" more)");
                break;
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength);
    }
}
