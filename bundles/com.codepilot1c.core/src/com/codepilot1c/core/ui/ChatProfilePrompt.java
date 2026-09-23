/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.ui;

import java.util.Objects;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.BuildAgentProfile;

/** Chat-only role guidance on top of the writable EDT workflow. */
public final class ChatProfilePrompt {

    private ChatProfilePrompt() {
    }

    /**
     * Keeps the selected role while using one writable tool workflow for chat.
     * The standalone agent's profile prompt may contain read-only instructions
     * and therefore cannot be reused for a profile-independent ChatView.
     */
    public static String forRole(AgentProfile selected, String writablePrompt) {
        Objects.requireNonNull(selected, "selected"); //$NON-NLS-1$
        Objects.requireNonNull(writablePrompt, "writablePrompt"); //$NON-NLS-1$
        if (BuildAgentProfile.ID.equals(selected.getId())) {
            return writablePrompt;
        }
        String focus = switch (selected.getId()) {
            case "plan" -> "Сначала анализируй и составляй проверяемый план. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Если запрос только о плане, не изменяй проект; если пользователь просит " //$NON-NLS-1$
                    + "реализацию, выполни изменения и проверки."; //$NON-NLS-1$
            case "explore" -> "Быстро находи релевантные факты и точные ссылки. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "Если запрос только об исследовании, не изменяй проект; если пользователь " //$NON-NLS-1$
                    + "просит реализацию, выполни изменения и проверки."; //$NON-NLS-1$
            case "orchestrator" -> "Координируй сложную работу и делегируй подходящие подзадачи."; //$NON-NLS-1$ //$NON-NLS-2$
            case "init" -> "Исследуй структуру проекта и подготовь полезный контекст."; //$NON-NLS-1$ //$NON-NLS-2$
            case "code" -> "Сосредоточься на BSL и коде проекта."; //$NON-NLS-1$ //$NON-NLS-2$
            case "metadata" -> "Сосредоточься на модели метаданных и EDT BM API."; //$NON-NLS-1$ //$NON-NLS-2$
            case "qa" -> "Сосредоточься на проверках, сценариях и диагностике."; //$NON-NLS-1$ //$NON-NLS-2$
            case "dcs" -> "Сосредоточься на схемах компоновки данных."; //$NON-NLS-1$ //$NON-NLS-2$
            case "extension" -> "Сосредоточься на расширениях конфигурации."; //$NON-NLS-1$ //$NON-NLS-2$
            case "recovery" -> "Сосредоточься на диагностике и восстановлении после ошибок."; //$NON-NLS-1$ //$NON-NLS-2$
            default -> selected.getId().startsWith("gsd-") //$NON-NLS-1$
                    ? "Веди выбранную фазу GSD; переходы и записи состояния выполняй " //$NON-NLS-1$
                            + "только через GSD-инструменты с их проверками." //$NON-NLS-1$
                    : "Сосредоточься на задачах выбранной специализации."; //$NON-NLS-1$
        };
        return "# Роль чата: " + selected.getName() + " (" + selected.getId() + ")\n" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + focus + "\n" //$NON-NLS-1$
                + "Роль задаёт способ работы и ответа. Доступ к инструментам определяется " //$NON-NLS-1$
                + "общими правилами разрешений, подтверждениями и проверками самих инструментов.\n\n" //$NON-NLS-1$
                + writablePrompt;
    }
}
