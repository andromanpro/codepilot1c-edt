package com.codepilot1c.core.tools.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.edt.observability.CommandRunner;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.java.probe.JavaCompileProbeRunner;
import com.codepilot1c.core.java.probe.JdkLocator;
import com.codepilot1c.core.java.probe.ProbeOutcome;
import com.codepilot1c.core.java.probe.ProbePayload;
import com.codepilot1c.core.java.probe.SnippetKind;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Compile-only Java syntax/API probe. It never evaluates the snippet. An
 * external javac process provides CPU/memory isolation while {@code -proc:none}
 * and empty class/source/processor paths keep the operation at Tier A0.
 *
 * <p>Read-only means that project/workspace state is not mutated. The helper
 * compiler process writes only to a fresh system temporary directory, which is
 * removed after every outcome.</p>
 */
@ToolMeta(
        name = "java_compile_probe", //$NON-NLS-1$
        category = "analysis", //$NON-NLS-1$
        surfaceCategory = "analysis", //$NON-NLS-1$
        mutating = false,
        requiresValidationToken = false,
        tags = {"read-only", "local-exec", "java"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
public final class JavaCompileProbeTool extends AbstractTool {

    public static final String ENABLED_PREFERENCE = "javaProbe.enabled"; //$NON-NLS-1$
    public static final String JDK_HOME_PREFERENCE = "javaProbe.jdkHome"; //$NON-NLS-1$
    public static final boolean DEFAULT_ENABLED = false;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "snippet": {"type": "string", "description": "Java snippet to type-check"},
                "snippet_kind": {
                  "type": "string",
                  "enum": ["AUTO", "EXPRESSION", "STATEMENTS", "DECLARATION", "COMPILATION_UNIT"],
                  "default": "AUTO"
                }
              },
              "required": ["snippet"],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final JavaCompileProbeRunner runner;
    private final BooleanSupplier enabled;

    public JavaCompileProbeTool() {
        this(defaultRunner(), JavaCompileProbeTool::isEnabledByPreference);
    }

    public JavaCompileProbeTool(JavaCompileProbeRunner runner, BooleanSupplier enabled) {
        this.runner = runner;
        this.enabled = enabled;
    }

    @Override
    public String getDescription() {
        return "Проверяет компиляцию Java-сниппета внешним javac без исполнения кода и без " //$NON-NLS-1$
                + "classpath проекта/EDT. Не вычисляет значение выражения. AUTO выполняет до " //$NON-NLS-1$
                + "четырёх попыток по 10 секунд."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> executeProbe(params));
    }

    private ToolResult executeProbe(ToolParameters params) {
        boolean probeEnabled = safelyEnabled();
        if (!probeEnabled) {
            return toToolResult(runner.run(false, null, SnippetKind.AUTO));
        }

        Object rawSnippet = params.getRaw().get("snippet"); //$NON-NLS-1$
        if (rawSnippet != null && !(rawSnippet instanceof String)) {
            return toToolResult(ProbeOutcome.failure("probe_internal_error", //$NON-NLS-1$
                    "snippet must be a string", "none")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Object rawKind = params.getRaw().get("snippet_kind"); //$NON-NLS-1$
        if (rawKind != null && !(rawKind instanceof String)) {
            return toToolResult(ProbeOutcome.failure("probe_internal_error", //$NON-NLS-1$
                    "snippet_kind must be a string", "none")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try {
            String snippet = (String) rawSnippet;
            SnippetKind kind = SnippetKind.parse((String) rawKind);
            return toToolResult(runner.run(true, snippet, kind));
        } catch (IllegalArgumentException e) {
            return toToolResult(ProbeOutcome.failure("probe_internal_error", //$NON-NLS-1$
                    "Unsupported snippet_kind", "none")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private boolean safelyEnabled() {
        try {
            return enabled != null && enabled.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ToolResult toToolResult(ProbeOutcome outcome) {
        if (!outcome.probeOk()) {
            return ToolResult.failure("Java compile probe недоступен: " + outcome.errorCode(), //$NON-NLS-1$
                    ProbePayload.toJson(outcome));
        }
        String prose = outcome.compiles()
                ? "Java-сниппет компилируется; код не исполнялся." //$NON-NLS-1$
                : "Java-сниппет не компилируется; код не исполнялся."; //$NON-NLS-1$
        return ToolResult.success(prose, ProbePayload.toJson(outcome));
    }

    private static JavaCompileProbeRunner defaultRunner() {
        List<Path> forbiddenRoots = new ArrayList<>();
        forbiddenRoots.add(Path.of("").toAbsolutePath().normalize()); //$NON-NLS-1$
        try {
            var location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
            if (location != null) {
                forbiddenRoots.add(location.toFile().toPath());
            }
        } catch (RuntimeException e) {
            // Workspace may be unavailable during early/headless initialization.
        }
        return new JavaCompileProbeRunner(
                CommandRunner.isolatedProcessBuilder(),
                JdkLocator.system(JavaCompileProbeTool::jdkHomePreference),
                forbiddenRoots);
    }

    private static boolean isEnabledByPreference() {
        return InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID)
                .getBoolean(ENABLED_PREFERENCE, DEFAULT_ENABLED);
    }

    private static String jdkHomePreference() {
        return InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID)
                .get(JDK_HOME_PREFERENCE, null);
    }
}
