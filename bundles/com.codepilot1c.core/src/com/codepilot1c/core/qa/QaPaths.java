package com.codepilot1c.core.qa;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class QaPaths {

    private QaPaths() {
        // Utility class.
    }

    public static File resolve(String path, File workspaceRoot) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        if (isWindowsAbsolute(trimmed)) {
            return new File(trimmed);
        }
        Path raw = Paths.get(trimmed);
        if (raw.isAbsolute()) {
            return raw.toFile();
        }
        if (workspaceRoot == null) {
            return raw.toFile();
        }
        return new File(workspaceRoot, trimmed);
    }

    public static File resolveConfigFile(String path, File workspaceRoot, String defaultRelative) {
        if (path == null || path.isBlank()) {
            return resolve(defaultRelative, workspaceRoot);
        }
        return resolve(path, workspaceRoot);
    }

    /**
     * Где именно взят qa-config. Возвращается наружу, чтобы промах «взяли конфиг чужого
     * проекта» был виден сразу в ответе тула, а не через час прогонов не на той базе.
     */
    public enum ConfigSource {
        /** Путь передан вызывающим явно. */
        EXPLICIT,
        /** Найден конфиг проекта: {@code <workspace>/<project>/tests/qa/qa-config.json}. */
        PROJECT,
        /** Фолбэк на общий конфиг воркспейса. */
        WORKSPACE_DEFAULT
    }

    /** Результат резолва: сам файл и то, откуда он взялся. */
    public record ResolvedConfig(File file, ConfigSource source, String projectName) {

        public String describe() {
            switch (source) {
                case EXPLICIT:
                    return "явно переданный config_path"; //$NON-NLS-1$
                case PROJECT:
                    return "конфиг проекта " + projectName; //$NON-NLS-1$
                default:
                    return "общий конфиг воркспейса (у проекта своего нет)"; //$NON-NLS-1$
            }
        }
    }

    /**
     * Куда СОЗДАВАТЬ qa-config. В отличие от {@link #resolveConfigForProject}, не проверяет
     * существование: если задан проект, конфиг всегда кладётся в
     * {@code <workspace>/<project>/tests/qa/qa-config.json}, чтобы у каждого проекта воркспейса
     * был свой, а не один общий на всех.
     */
    public static File resolveConfigTarget(String path, File workspaceRoot, String projectName,
                                           String defaultRelative) {
        if (path != null && !path.isBlank()) {
            return resolve(path, workspaceRoot);
        }
        if (projectName != null && !projectName.isBlank() && workspaceRoot != null) {
            return new File(new File(workspaceRoot, projectName), defaultRelative);
        }
        return resolve(defaultRelative, workspaceRoot);
    }

    /**
     * Резолвит qa-config с учётом проекта. Порядок: явный путь → конфиг проекта
     * {@code <workspace>/<project>/tests/qa/qa-config.json} → общий конфиг воркспейса.
     *
     * <p>Без этого в воркспейсе с несколькими проектами все QA-тулы читали один и тот же
     * {@code <workspace>/tests/qa/qa-config.json} — то есть настройки и launch-конфигурацию
     * того проекта, кто настроил их последним, независимо от того, с каким работают сейчас.
     */
    public static ResolvedConfig resolveConfigForProject(String path, File workspaceRoot,
                                                        String projectName, String defaultRelative) {
        if (path != null && !path.isBlank()) {
            return new ResolvedConfig(resolve(path, workspaceRoot), ConfigSource.EXPLICIT, projectName);
        }
        if (projectName != null && !projectName.isBlank() && workspaceRoot != null) {
            File projectConfig = new File(new File(workspaceRoot, projectName), defaultRelative);
            if (projectConfig.isFile()) {
                return new ResolvedConfig(projectConfig, ConfigSource.PROJECT, projectName);
            }
        }
        return new ResolvedConfig(resolve(defaultRelative, workspaceRoot), ConfigSource.WORKSPACE_DEFAULT,
                projectName);
    }

    public static boolean isWithinWorkspace(File workspaceRoot, File target) {
        if (workspaceRoot == null || target == null) {
            return false;
        }
        try {
            String rootPath = workspaceRoot.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            return targetPath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            String rootPath = workspaceRoot.getAbsolutePath();
            String targetPath = target.getAbsolutePath();
            return targetPath.startsWith(rootPath + File.separator);
        }
    }

    private static boolean isWindowsAbsolute(String path) {
        if (path == null || path.length() < 3) {
            return false;
        }
        char drive = path.charAt(0);
        char colon = path.charAt(1);
        char slash = path.charAt(2);
        return ((drive >= 'A' && drive <= 'Z') || (drive >= 'a' && drive <= 'z'))
                && colon == ':'
                && (slash == '\\' || slash == '/');
    }
}
