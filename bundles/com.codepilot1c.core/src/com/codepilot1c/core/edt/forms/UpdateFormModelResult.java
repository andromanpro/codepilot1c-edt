package com.codepilot1c.core.edt.forms;

import java.util.List;

/**
 * Result of managed form model mutation.
 */
public record UpdateFormModelResult(
        String projectName,
        String formFqn,
        int operationsApplied,
        List<String> operationSummaries,
        HandlerStubReport handlerStubs
) {
    public UpdateFormModelResult {
        operationSummaries = operationSummaries == null ? List.of() : List.copyOf(operationSummaries);
        handlerStubs = handlerStubs == null ? HandlerStubReport.empty() : handlerStubs;
    }

    public List<String> handlerStubsWritten() {
        return handlerStubs.written();
    }

    public List<String> handlerStubsSkippedExisting() {
        return handlerStubs.skippedExisting();
    }

    public String formatForLlm() {
        StringBuilder details = new StringBuilder();
        for (String operationSummary : operationSummaries) {
            if (operationSummary == null || operationSummary.isBlank()) {
                continue;
            }
            details.append("- ").append(operationSummary).append('\n'); //$NON-NLS-1$
        }
        String header = handlerStubs.header(
                "✅ Модель формы обновлена.", //$NON-NLS-1$
                "⚠️ Модель формы обновлена ЧАСТИЧНО: часть заглушек не записана (процедуры уже существуют)."); //$NON-NLS-1$
        return """
                %s
                Проект: %s
                Форма: %s
                Применено операций: %d
                %s
                %sРекомендуется проверить get_diagnostics (scope=file и scope=project).
                """.formatted(
                header,
                safe(projectName),
                safe(formFqn),
                Integer.valueOf(operationsApplied),
                details.toString(),
                handlerStubs.formatForLlm());
    }

    private String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
