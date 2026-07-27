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
 * Профиль агента определяет его возможности и ограничения.
 *
 * <p>Профили позволяют создавать агентов с разным уровнем доступа:</p>
 * <ul>
 *   <li><b>build</b> - полный доступ для разработки (чтение, запись, shell)</li>
 *   <li><b>plan</b> - только чтение для анализа и планирования</li>
 *   <li><b>explore</b> - быстрый поиск по кодовой базе</li>
 * </ul>
 */
public interface AgentProfile {

    /**
     * Уникальный идентификатор профиля.
     *
     * @return ID профиля (например, "build", "plan", "explore")
     */
    String getId();

    /**
     * Отображаемое имя профиля.
     *
     * @return имя на русском
     */
    String getName();

    /**
     * Описание профиля.
     *
     * @return описание возможностей
     */
    String getDescription();

    /**
     * Разрешенные инструменты для этого профиля.
     *
     * @return набор имен инструментов
     */
    Set<String> getAllowedTools();

    /**
     * Правила разрешений по умолчанию.
     *
     * @return список правил
     */
    List<PermissionRule> getDefaultPermissions();

    /**
     * Дополнение к системному промпту.
     *
     * @return текст для добавления в system prompt или null
     */
    String getSystemPromptAddition();

    /**
     * Максимальное количество шагов.
     *
     * @return лимит шагов
     */
    int getMaxSteps();

    /**
     * Таймаут выполнения в миллисекундах.
     *
     * @return таймаут
     */
    long getTimeoutMs();

    /**
     * Является ли профиль read-only (без модификации файлов).
     *
     * @return true если только чтение
     */
    boolean isReadOnly();

    /**
     * Может ли профиль выполнять shell команды.
     *
     * @return true если shell разрешен
     */
    boolean canExecuteShell();

}
