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
 * Result of apply_form_recipe execution.
 */
public record FormRecipeResult(
        String projectName,
        String formFqn,
        int attributesCreated,
        int attributesUpdated,
        int attributesRemoved,
        int layoutOperationsApplied,
        List<String> layoutOperationSummaries,
        List<String> handlerStubsWritten,
        List<String> handlerStubsSkippedExisting
) {
    public FormRecipeResult {
        layoutOperationSummaries = immutableList(layoutOperationSummaries);
        handlerStubsWritten = immutableList(handlerStubsWritten);
        handlerStubsSkippedExisting = immutableList(handlerStubsSkippedExisting);
    }

    public String formatForLlm() {
        boolean hasSkippedStubs = !handlerStubsSkippedExisting.isEmpty();
        StringBuilder summaries = new StringBuilder();
        for (String summary : layoutOperationSummaries) {
            if (summary.isBlank()) {
                continue;
            }
            summaries.append("- ").append(summary).append('\n'); //$NON-NLS-1$
        }
        StringBuilder writtenDetails = new StringBuilder();
        for (String handlerName : handlerStubsWritten) {
            writtenDetails.append("- ").append(safe(handlerName)).append('\n'); //$NON-NLS-1$
        }
        StringBuilder skippedDetails = new StringBuilder();
        if (hasSkippedStubs) {
            for (String handlerName : handlerStubsSkippedExisting) {
                skippedDetails.append("- ").append(safe(handlerName)) //$NON-NLS-1$
                        .append(": процедура с таким именем уже существует в модуле — тело не изменено\n"); //$NON-NLS-1$
            }
            skippedDetails.append(
                    "Проверьте тело процедуры в Module.bsl и get_diagnostics (scope=file и scope=project).\n"); //$NON-NLS-1$
        }
        String header = hasSkippedStubs
                ? "⚠️ Рецепт формы применен ЧАСТИЧНО: часть заглушек не записана (процедуры уже существуют)." //$NON-NLS-1$
                : "✅ Рецепт формы применен."; //$NON-NLS-1$
        return """
                %s
                Проект: %s
                Форма: %s
                Создано реквизитов формы: %d
                Обновлено реквизитов формы: %d
                Удалено реквизитов формы: %d
                Операций по макету: %d
                %s
                Записано заглушек обработчиков: %d
                %s
                Пропущено заглушек (уже существуют): %d
                %s
                Рекомендуется проверить get_diagnostics (scope=file и scope=project).
                """.formatted(
                header,
                safe(projectName),
                safe(formFqn),
                Integer.valueOf(attributesCreated),
                Integer.valueOf(attributesUpdated),
                Integer.valueOf(attributesRemoved),
                Integer.valueOf(layoutOperationsApplied),
                summaries.toString(),
                Integer.valueOf(handlerStubsWritten.size()),
                writtenDetails.toString(),
                Integer.valueOf(handlerStubsSkippedExisting.size()),
                skippedDetails.toString());
    }

    private static List<String> immutableList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
