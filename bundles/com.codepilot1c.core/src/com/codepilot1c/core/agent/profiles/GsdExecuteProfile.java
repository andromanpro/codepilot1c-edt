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
 * GSD Execute-фаза: реализация плана минимальными обратимыми изменениями.
 *
 * <p>Включает ограниченный набор project/EDT мутаций: редактирование файлов,
 * подготовку артефактов модулей, обновление задач. Перед EDT-мутациями
 * обязателен edt_validate_request; validation_token не обходится.</p>
 */
public final class GsdExecuteProfile extends GsdPhaseProfile {

    public static final String ID = "gsd-execute"; //$NON-NLS-1$

    private static final Set<String> ALLOWED_TOOLS = extendTools(
            "gsd_get_state", //$NON-NLS-1$
            "gsd_update_task", //$NON-NLS-1$
            "gsd_transition", //$NON-NLS-1$
            "edt_validate_request", //$NON-NLS-1$
            "edit_file", //$NON-NLS-1$
            "write_file", //$NON-NLS-1$
            "ensure_module_artifact", //$NON-NLS-1$
            "remember_fact" //$NON-NLS-1$
    );

    private static final List<PermissionRule> DEFAULT_PERMISSIONS = extendPermissions(
            PermissionRule.allow("gsd_get_state").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_update_task").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("gsd_transition").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("edt_validate_request").forAllResources(), //$NON-NLS-1$
            PermissionRule.allow("remember_fact").forAllResources(), //$NON-NLS-1$
            PermissionRule.ask("edit_file") //$NON-NLS-1$
                    .withDescription("Редактирование файлов проекта") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.ask("write_file") //$NON-NLS-1$
                    .withDescription("Создание файлов проекта") //$NON-NLS-1$
                    .forAllResources(),
            PermissionRule.ask("ensure_module_artifact") //$NON-NLS-1$
                    .withDescription("Создание/подготовка файлов модулей EDT") //$NON-NLS-1$
                    .forAllResources()
    );

    public GsdExecuteProfile() {
        super(
                ID,
                "GSD Исполнение", //$NON-NLS-1$
                "Фаза GSD: реализация задач плана. Допускает минимальные " //$NON-NLS-1$
                        + "project/EDT мутации только через edt_validate_request + validation_token.", //$NON-NLS-1$
                ALLOWED_TOOLS,
                DEFAULT_PERMISSIONS,
                40,
                10 * 60 * 1000L,
                false);
    }
}
