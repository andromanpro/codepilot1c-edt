/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.forms;

import java.util.List;

/**
 * Shared post-export handler-stub report for {@code apply_form_recipe} and
 * {@code mutate_form_model}.
 * <p>
 * A skipped entry covers both a stub that was not needed and one that was not
 * written because a procedure with the same name already exists. Distinguishing
 * those outcomes requires a separate writer contract; this report deliberately
 * preserves the existing result semantics.
 */
public record HandlerStubReport(
        List<String> written,
        List<String> skippedExisting
) {
    public HandlerStubReport {
        written = immutableList(written);
        skippedExisting = immutableList(skippedExisting);
    }

    public static HandlerStubReport empty() {
        return new HandlerStubReport(List.of(), List.of());
    }

    /**
     * Returns whether every requested stub was written, not whether the form is
     * otherwise valid or complete.
     */
    public boolean complete() {
        return skippedExisting.isEmpty();
    }

    public String header(String successText, String partialText) {
        return complete() ? safe(successText) : safe(partialText);
    }

    /**
     * Formats the shared written/skipped block for model-facing output.
     */
    public String formatForLlm() {
        StringBuilder details = new StringBuilder();
        details.append("Записано заглушек обработчиков: ").append(written.size()).append('\n'); //$NON-NLS-1$
        for (String handlerName : written) {
            details.append("- ").append(safe(handlerName)).append('\n'); //$NON-NLS-1$
        }
        details.append('\n');
        details.append("Пропущено заглушек (уже существуют): ") //$NON-NLS-1$
                .append(skippedExisting.size()).append('\n');
        for (String handlerName : skippedExisting) {
            details.append("- ").append(safe(handlerName)) //$NON-NLS-1$
                    .append(": процедура с таким именем уже существует в модуле — тело не изменено\n"); //$NON-NLS-1$
        }
        if (!skippedExisting.isEmpty()) {
            details.append(
                    "Проверьте тело процедуры в Module.bsl и get_diagnostics (scope=file и scope=project).\n"); //$NON-NLS-1$
        }
        details.append('\n');
        return details.toString();
    }

    private static List<String> immutableList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
