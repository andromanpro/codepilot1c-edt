package com.codepilot1c.core.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Machine gate for antipattern #72 ("silent platform failures"): assigning a
 * string to a reference attribute is silently accepted by the platform and
 * yields an empty reference — the classic case is the БСП access-group profile
 * tabular section {@code Роли.Роль}, typed as a reference to
 * {@code ПВХ.ИдентификаторыОбъектовМетаданных}: a role-name string produces an
 * empty reference and the profile loses its roles without any error.
 *
 * <p>The linter is a WARNING layer, not a blocker: an attribute named
 * {@code Роль} may legitimately be a string in some configuration, so the
 * finding instructs the agent to check the attribute type and to re-read the
 * written value (round-trip) instead of refusing the write.</p>
 */
public final class BslSilentTypeLinter {

    /**
     * Attribute names known to be metadata-object-id references in БСП.
     * Extend deliberately: every name here fires a warning on
     * {@code <expr>.<Name> = "<string literal>"}.
     */
    private static final List<String> METADATA_ID_REFERENCE_ATTRIBUTES = List.of("Роль"); //$NON-NLS-1$

    private static final Pattern STRING_ASSIGNMENT = Pattern.compile(
            "\\.\\s*(" + String.join("|", METADATA_ID_REFERENCE_ATTRIBUTES) + ")\\s*=\\s*\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** SEARCH/REPLACE block body between the ======= separator and the REPLACE terminator. */
    private static final Pattern REPLACE_SEGMENT = Pattern.compile(
            "^=======\\s*$(.*?)^>{5,9}\\s*REPLACE\\s*$", Pattern.MULTILINE | Pattern.DOTALL); //$NON-NLS-1$

    private BslSilentTypeLinter() {
    }

    /** {@code true} for files where the check applies (BSL modules). */
    public static boolean isBslPath(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".bsl"); //$NON-NLS-1$
    }

    /**
     * Lints inserted BSL code; returns one message per finding (empty list = clean).
     * Comment-only lines are skipped.
     */
    public static List<String> lint(String code) {
        List<String> warnings = new ArrayList<>();
        if (code == null || code.isEmpty()) {
            return warnings;
        }
        String[] lines = code.split("\n", -1); //$NON-NLS-1$
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().startsWith("//")) { //$NON-NLS-1$
                continue;
            }
            Matcher matcher = STRING_ASSIGNMENT.matcher(line);
            if (matcher.find()) {
                warnings.add("строка " + (i + 1) + ": строковый литерал присваивается реквизиту «" //$NON-NLS-1$ //$NON-NLS-2$
                        + matcher.group(1)
                        + "» — в БСП (ПрофилиГруппДоступа.Роли.Роль) это ссылка на " //$NON-NLS-1$
                        + "ПВХ ИдентификаторыОбъектовМетаданных: строка молча даст пустую ссылку, роли потеряются. " //$NON-NLS-1$
                        + "Используй ОбщегоНазначения.ИдентификаторОбъектаМетаданных(\"Роль.ИмяРоли\") " //$NON-NLS-1$
                        + "и перечитай записанное значение после записи (round-trip). " //$NON-NLS-1$
                        + "Если реквизит в этой конфигурации действительно строковый — предупреждение можно игнорировать."); //$NON-NLS-1$
            }
        }
        return warnings;
    }

    /**
     * Lints only the REPLACE halves of a SEARCH/REPLACE {@code edits} payload,
     * so pre-existing code inside SEARCH parts never triggers findings.
     * Line numbers in findings are local to each REPLACE segment.
     */
    public static List<String> lintReplaceSegments(String editsBlocks) {
        List<String> warnings = new ArrayList<>();
        if (editsBlocks == null || editsBlocks.isEmpty()) {
            return warnings;
        }
        Matcher matcher = REPLACE_SEGMENT.matcher(editsBlocks);
        boolean segmentFound = false;
        while (matcher.find()) {
            segmentFound = true;
            warnings.addAll(lint(matcher.group(1)));
        }
        if (!segmentFound) {
            // Not the SEARCH/REPLACE shape — lint the payload as-is.
            warnings.addAll(lint(editsBlocks));
        }
        return warnings;
    }

    /**
     * Ready-to-append warning block for a tool result, or {@code null} when clean
     * or the path is not a BSL module.
     */
    public static String formatForResult(String path, List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        StringBuilder block = new StringBuilder();
        block.append("⚠ Контроль типов (антипаттерн #72 — молчаливые отказы платформы)"); //$NON-NLS-1$
        if (path != null && !path.isBlank()) {
            block.append(" в ").append(path); //$NON-NLS-1$
        }
        block.append(":"); //$NON-NLS-1$
        for (String warning : warnings) {
            block.append("\n- ").append(warning); //$NON-NLS-1$
        }
        return block.toString();
    }
}
