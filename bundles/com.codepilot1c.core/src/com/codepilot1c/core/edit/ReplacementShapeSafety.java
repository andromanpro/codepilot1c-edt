/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edit;

import java.util.regex.Pattern;

/**
 * Defensive post-edit guard for fuzzy replacements: rejects a result whose text
 * contains an <em>unambiguously glued</em> BSL procedure/function boundary — the
 * corruption shape produced when a fuzzy match collapses a line break between two
 * declarations or between an ending and the next declaration.
 *
 * <p>This is a deliberate <strong>safe subset</strong> of the upstream branch
 * {@code fix/edit-file-fuzzy-safety} ({@code ReplacementShapeSafety}, commit
 * {@code d2a46c6}). Only the three zero-false-positive detectors are kept; the
 * false-positive-prone heuristics from upstream are intentionally omitted:
 * the {@code \S;\t\S} "semicolon+tab" rule (matches legal {@code Код;\t//коммент}),
 * the duplicated-call-on-one-line pre-check (matches legal {@code F(a); F(b);}),
 * and the removed-multiline-arguments pre-check. Those would block legitimate edits
 * on the project's most-used tool.</p>
 *
 * <p>The patterns are BSL-specific Cyrillic keywords, so the guard is a no-op on
 * non-BSL content (XML, plain text) and never false-positives there.</p>
 */
public final class ReplacementShapeSafety {

    /** Two procedure/function endings glued with no separator at all. */
    private static final Pattern GLUED_BSL_END = Pattern.compile(
            "(?iu)(КонецПроцедурыКонецПроцедуры|КонецФункцииКонецФункции)"); //$NON-NLS-1$
    /** An ending glued to the next declaration on the same line. */
    private static final Pattern GLUED_BSL_END_TO_DECLARATION = Pattern.compile(
            "(?iu)(КонецПроцедуры|КонецФункции)[ \\t]*(Процедура|Функция)"); //$NON-NLS-1$
    /** Two declarations glued on the same line (the second starts right after the first's `)`). */
    private static final Pattern GLUED_BSL_DECLARATION = Pattern.compile(
            "(?iu)(Процедура|Функция)[^\\r\\n]*\\)[ \\t]*(Процедура|Функция)"); //$NON-NLS-1$

    private ReplacementShapeSafety() {
    }

    /**
     * Inspects the result fragment of a fuzzy replacement. Returns {@link SafetyResult#unsafe}
     * only when the text contains glued BSL procedure/function boundaries that cannot occur in
     * valid source. A {@code null} fragment is treated as safe (non-blocking).
     */
    public static SafetyResult evaluateResult(String resultFragment) {
        if (resultFragment == null) {
            return SafetyResult.safe();
        }
        String normalized = resultFragment.replace("\r\n", "\n").replace('\r', '\n'); //$NON-NLS-1$ //$NON-NLS-2$
        if (GLUED_BSL_END.matcher(normalized).find()) {
            return SafetyResult.unsafe(
                    "Небезопасная fuzzy-правка: склеены окончания процедур/функций " //$NON-NLS-1$
                            + "(например, КонецПроцедурыКонецПроцедуры). Уточните old_text."); //$NON-NLS-1$
        }
        if (GLUED_BSL_END_TO_DECLARATION.matcher(normalized).find()) {
            return SafetyResult.unsafe(
                    "Небезопасная fuzzy-правка: склеены конец и начало процедуры/функции " //$NON-NLS-1$
                            + "(например, КонецПроцедуры Процедура). Уточните old_text."); //$NON-NLS-1$
        }
        if (GLUED_BSL_DECLARATION.matcher(normalized).find()) {
            return SafetyResult.unsafe(
                    "Небезопасная fuzzy-правка: склеены объявления процедур/функций на одной строке. " //$NON-NLS-1$
                            + "Уточните old_text."); //$NON-NLS-1$
        }
        return SafetyResult.safe();
    }

    /** Outcome of a shape-safety check. */
    public static final class SafetyResult {

        private final boolean safe;
        private final String reason;

        private SafetyResult(boolean safe, String reason) {
            this.safe = safe;
            this.reason = reason;
        }

        public static SafetyResult safe() {
            return new SafetyResult(true, null);
        }

        public static SafetyResult unsafe(String reason) {
            return new SafetyResult(false, reason);
        }

        public boolean isSafe() {
            return safe;
        }

        public String reason() {
            return reason;
        }
    }
}
