/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.logging;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Централизованный логгер для Vibe плагина.
 *
 * <p>Поддерживает:</p>
 * <ul>
 *   <li>Логирование в Eclipse log</li>
 *   <li>Логирование в файл</li>
 *   <li>Callback для UI отображения</li>
 *   <li>Фильтрацию по уровню</li>
 * </ul>
 */
public class VibeLogger {

    private static final VibeLogger INSTANCE = new VibeLogger();
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"); //$NON-NLS-1$
    private static final int MAX_MEMORY_LOGS = 1000;

    /**
     * Уровни логирования.
     */
    public enum Level {
        DEBUG(0),
        INFO(1),
        WARN(2),
        ERROR(3);

        private final int severity;

        Level(int severity) {
            this.severity = severity;
        }

        public int getSeverity() {
            return severity;
        }
    }

    /**
     * Запись лога.
     */
    public static class LogEntry {
        private final LocalDateTime timestamp;
        private final Level level;
        private final String category;
        private final String message;
        private final Throwable throwable;

        public LogEntry(Level level, String category, String message, Throwable throwable) {
            this.timestamp = LocalDateTime.now();
            this.level = level;
            this.category = category;
            this.message = message;
            this.throwable = throwable;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public Level getLevel() {
            return level;
        }

        public String getCategory() {
            return category;
        }

        public String getMessage() {
            return message;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(TIMESTAMP_FORMAT.format(timestamp)).append("] "); //$NON-NLS-1$
            sb.append('[').append(level.name()).append("] "); //$NON-NLS-1$
            sb.append('[').append(category).append("] "); //$NON-NLS-1$
            sb.append(message);

            if (throwable != null) {
                sb.append('\n');
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                sb.append(sw.toString());
            }

            return sb.toString();
        }
    }

    private Level minLevel = Level.DEBUG;
    private boolean logToEclipse = true;
    private boolean logToFile = false;
    private Path logFilePath;
    private final List<LogEntry> memoryLog = new CopyOnWriteArrayList<>();
    private final List<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();

    private VibeLogger() {
        // Configure default log file path
        try {
            Path stateLocation = Platform.getStateLocation(
                    FrameworkUtil.getBundle(VibeLogger.class)).toPath();
            logFilePath = stateLocation.resolve("vibe.log"); //$NON-NLS-1$
        } catch (Exception e) {
            // Fallback to temp directory
            logFilePath = Path.of(System.getProperty("java.io.tmpdir"), "vibe.log"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Возвращает singleton экземпляр.
     */
    public static VibeLogger getInstance() {
        return INSTANCE;
    }

    // --- Configuration ---

    /**
     * Устанавливает минимальный уровень логирования.
     */
    public void setMinLevel(Level level) {
        this.minLevel = level;
    }

    /**
     * Включает/выключает логирование в Eclipse log.
     */
    public void setLogToEclipse(boolean enabled) {
        this.logToEclipse = enabled;
    }

    /**
     * Включает/выключает логирование в файл.
     */
    public void setLogToFile(boolean enabled) {
        this.logToFile = enabled;
    }

    /**
     * Устанавливает путь к лог-файлу.
     */
    public void setLogFilePath(Path path) {
        this.logFilePath = path;
    }

    /**
     * Возвращает путь к лог-файлу.
     */
    public Path getLogFilePath() {
        return logFilePath;
    }

    /**
     * Добавляет listener для новых записей лога.
     */
    public void addListener(Consumer<LogEntry> listener) {
        listeners.add(listener);
    }

    /**
     * Удаляет listener.
     */
    public void removeListener(Consumer<LogEntry> listener) {
        listeners.remove(listener);
    }

    // --- Logging methods ---

    /**
     * Логирует DEBUG сообщение.
     */
    public void debug(String category, String message) {
        log(Level.DEBUG, category, message, null);
    }

    /**
     * Логирует DEBUG с форматированием.
     */
    public void debug(String category, String format, Object... args) {
        log(Level.DEBUG, category, String.format(format, args), null);
    }

    /**
     * Логирует INFO сообщение.
     */
    public void info(String category, String message) {
        log(Level.INFO, category, message, null);
    }

    /**
     * Логирует INFO с форматированием.
     */
    public void info(String category, String format, Object... args) {
        log(Level.INFO, category, String.format(format, args), null);
    }

    /**
     * Логирует WARN сообщение.
     */
    public void warn(String category, String message) {
        log(Level.WARN, category, message, null);
    }

    /**
     * Логирует WARN с исключением.
     */
    public void warn(String category, String message, Throwable t) {
        log(Level.WARN, category, message, t);
    }

    /**
     * Логирует ERROR сообщение.
     */
    public void error(String category, String message) {
        log(Level.ERROR, category, message, null);
    }

    /**
     * Логирует ERROR с исключением.
     */
    public void error(String category, String message, Throwable t) {
        log(Level.ERROR, category, message, t);
    }

    /**
     * Основной метод логирования.
     */
    public void log(Level level, String category, String message, Throwable throwable) {
        if (level.getSeverity() < minLevel.getSeverity()) {
            return;
        }

        LogEntry entry = new LogEntry(level, category, message, throwable);

        // Store in memory (with limit)
        memoryLog.add(entry);
        while (memoryLog.size() > MAX_MEMORY_LOGS) {
            memoryLog.remove(0);
        }

        // Notify listeners
        for (Consumer<LogEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (Exception e) {
                // Ignore listener errors
            }
        }

        // Log to Eclipse
        if (logToEclipse) {
            logToEclipse(entry);
        }

        // Log to file
        if (logToFile) {
            logToFile(entry);
        }
    }

    private void logToEclipse(LogEntry entry) {
        try {
            Bundle bundle = FrameworkUtil.getBundle(VibeLogger.class);
            if (bundle == null) {
                return;
            }

            ILog log = Platform.getLog(bundle);
            int severity;
            switch (entry.getLevel()) {
                case DEBUG:
                case INFO:
                    severity = IStatus.INFO;
                    break;
                case WARN:
                    severity = IStatus.WARNING;
                    break;
                case ERROR:
                    severity = IStatus.ERROR;
                    break;
                default:
                    severity = IStatus.INFO;
            }

            String fullMessage = "[" + entry.getCategory() + "] " + entry.getMessage(); //$NON-NLS-1$ //$NON-NLS-2$
            log.log(new Status(severity, bundle.getSymbolicName(), fullMessage, entry.getThrowable()));
        } catch (Throwable failure) {
            // Surefire can load Platform outside the framework classloader.
            // Keep the diagnostic visible rather than letting a logging
            // boundary failure hide the original operation or disappear.
            System.err.println("Eclipse log unavailable: " + failure); //$NON-NLS-1$
            System.err.println(entry.toString());
        }
    }

    private void logToFile(LogEntry entry) {
        if (logFilePath == null) {
            return;
        }

        try {
            Files.writeString(logFilePath, entry.toString() + "\n", //$NON-NLS-1$
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Ignore file write errors
        }
    }

    // --- Memory log access ---

    /**
     * Возвращает копию всех записей из памяти.
     */
    public List<LogEntry> getMemoryLog() {
        return Collections.unmodifiableList(new ArrayList<>(memoryLog));
    }

    /**
     * Возвращает записи с определённого уровня.
     */
    public List<LogEntry> getMemoryLog(Level minLevel) {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : memoryLog) {
            if (entry.getLevel().getSeverity() >= minLevel.getSeverity()) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Очищает память логов.
     */
    public void clearMemoryLog() {
        memoryLog.clear();
    }

    /**
     * Очищает лог-файл.
     */
    public void clearLogFile() {
        if (logFilePath != null) {
            try {
                Files.deleteIfExists(logFilePath);
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    // --- Convenience factory methods ---

    /**
     * Создаёт логгер для категории.
     */
    public static CategoryLogger forCategory(String category) {
        return new CategoryLogger(category);
    }

    /**
     * Создаёт логгер для класса.
     */
    public static CategoryLogger forClass(Class<?> clazz) {
        return new CategoryLogger(clazz.getSimpleName());
    }

    /**
     * Обёртка для логирования с предустановленной категорией.
     */
    public static class CategoryLogger {
        private final String category;

        public CategoryLogger(String category) {
            this.category = category;
        }

        public void debug(String message) {
            INSTANCE.debug(category, message);
        }

        public void debug(String format, Object... args) {
            INSTANCE.debug(category, format, args);
        }

        public void info(String message) {
            INSTANCE.info(category, message);
        }

        public void info(String format, Object... args) {
            INSTANCE.info(category, format, args);
        }

        public void warn(String message) {
            INSTANCE.warn(category, message);
        }

        public void warn(String format, Object... args) {
            INSTANCE.log(Level.WARN, category, String.format(format, args), null);
        }

        public void warn(String message, Throwable t) {
            INSTANCE.warn(category, message, t);
        }

        public void error(String message) {
            INSTANCE.error(category, message);
        }

        public void error(String format, Object... args) {
            INSTANCE.log(Level.ERROR, category, String.format(format, args), null);
        }

        public void error(String message, Throwable t) {
            INSTANCE.error(category, message, t);
        }
    }
}
