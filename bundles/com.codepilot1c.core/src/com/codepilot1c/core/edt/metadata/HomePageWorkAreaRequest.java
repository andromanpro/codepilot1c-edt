/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

import java.util.List;

/**
 * Запрос на запись рабочей области начальной страницы проекта.
 *
 * @param projectName имя проекта EDT
 * @param template литерал шаблона ({@link HomePageWorkAreaXml#TEMPLATES}); пусто - по умолчанию
 * @param left формы левой (единственной) колонки
 * @param right формы правой колонки
 * @param allowSupportedObjectEdit явное согласие править объект типовой конфигурации под замком (антипаттерн #70)
 */
public record HomePageWorkAreaRequest(
        String projectName,
        String template,
        List<HomePageWorkAreaXml.Item> left,
        List<HomePageWorkAreaXml.Item> right,
        boolean allowSupportedObjectEdit
) {
}
