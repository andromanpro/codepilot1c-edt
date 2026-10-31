/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

/**
 * Результат записи рабочей области начальной страницы.
 *
 * @param projectName имя проекта
 * @param path путь файла в workspace
 * @param created файл создан (иначе перезаписан)
 * @param template литерал шаблона, который записан
 * @param formsCount сколько форм положено в колонки
 * @param bmSynced дождались ли пересчёта BM после записи
 */
public record HomePageWorkAreaResult(
        String projectName,
        String path,
        boolean created,
        String template,
        int formsCount,
        boolean bmSynced
) {
    public String formatForLlm() {
        StringBuilder sb = new StringBuilder();
        sb.append(created ? "Home page work area created" : "Home page work area rewritten"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append('\n');
        sb.append("Project: ").append(projectName); //$NON-NLS-1$
        sb.append('\n');
        sb.append("Path: ").append(path); //$NON-NLS-1$
        sb.append('\n');
        sb.append("Template: ").append(template); //$NON-NLS-1$
        sb.append('\n');
        sb.append("Forms: ").append(formsCount); //$NON-NLS-1$
        sb.append('\n');
        sb.append("BM sync: ").append(bmSynced ? "done" : "not confirmed (model may lag; run update_infobase later)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return sb.toString();
    }
}
