/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.List;
import java.util.Set;

import com.codepilot1c.core.permissions.PermissionRule;

/**
 * GSD Ship-фаза: финализация и доставка изменений.
 *
 * <p>Отвечает только за git/release-артефакты. Допускает минимальные
 * git-операции и запись release-нот. Помнит факты. Не выполняет и
 * не предполагает EDT-мутаций.</p>
 */
public final class GsdShipProfile extends GsdPhaseProfile {

    public static final String ID = "gsd-ship"; //$NON-NLS-1$

    private static final Set<String> ALLOWED_TOOLS = extendTools(
            "gsd_get_state", //$NON-NLS-1$
            "gsd_transition", //$NON-NLS-1$
            "git_mutate", //$NON-NLS-1$
            "write_file", //$NON-NLS-1$
            "remember_fact" //$NON-NLS-1$
    );

    private static final List<PermissionRule> DEFAULT_PERMISSIONS = extendPermissions(
            PermissionRule.allow("gsd_get_state").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_transition").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("remember_fact").forAllResources(), //$NON-NLS-1$
            PermissionRule.ask("git_mutate") //$NON-NLS-1$
                    .withDescription("Мутирующие git-операции") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.deny("write_file") //$NON-NLS-1$
                    .withDescription("Прямая запись .mdo запрещена; используй EDT mutation tools") //$NON-NLS-1$
                    .forResourcePattern("**/*.mdo") //$NON-NLS-1$
                    .build(),
            PermissionRule.ask("write_file") //$NON-NLS-1$
                    .withDescription("Создание release-артефактов") //$NON-NLS-1$
                    .forAllResources()
    );

    public GsdShipProfile() {
        super(
                ID,
                "GSD Доставка", //$NON-NLS-1$
                "Фаза GSD: финализация, коммит/пуш и release-артефакты. " //$NON-NLS-1$
                        + "Мутации только git и минимально необходимые файлы; не использует EDT-мутации.", //$NON-NLS-1$
                ALLOWED_TOOLS,
                DEFAULT_PERMISSIONS,
                25,
                6 * 60 * 1000L,
                false);
    }
}
