/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.permissions;

import java.util.List;
import java.util.Map;

/**
 * Объединяет профильный и глобальный слои permission-правил.
 */
public final class ProfilePermissionGate {

    /** Итоговое решение runtime-гейта. */
    public enum GateDecision {
        ALLOW,
        ASK,
        DENY,
        NO_RULE
    }

    /**
     * Результат проверки.
     *
     * @param decision итоговое решение
     * @param rule выигравшее правило, либо null для {@link GateDecision#NO_RULE}
     * @param layer слой выигравшего правила: profile, global или none
     */
    public record GateResult(GateDecision decision, PermissionRule rule, String layer) {
        /**
         * @return true, если выполнение должно быть запрещено
         */
        public boolean isDenied() {
            return decision == GateDecision.DENY;
        }
    }

    private ProfilePermissionGate() {
    }

    /**
     * Проверяет вызов по принципу самого строгого решения:
     * DENY &gt; ASK &gt; ALLOW, а отсутствие правила не влияет на второй слой.
     *
     * @param profileRules правила активного профиля
     * @param globalRules глобальные правила
     * @param toolName имя инструмента
     * @param arguments аргументы инструмента
     * @return итоговое решение с выигравшим правилом и слоем
     */
    public static GateResult evaluate(
            List<PermissionRule> profileRules,
            List<PermissionRule> globalRules,
            String toolName,
            Map<String, Object> arguments) {
        String resource = PermissionEvaluator.normalizedResourceOf(arguments);
        PermissionRule profileRule = PermissionEvaluator
                .strictestMatch(profileRules, toolName, resource)
                .orElse(null);
        PermissionRule globalRule = PermissionEvaluator
                .strictestMatch(globalRules, toolName, resource)
                .orElse(null);

        if (profileRule == null && globalRule == null) {
            return new GateResult(GateDecision.NO_RULE, null, "none");
        }
        if (hasDecision(profileRule, PermissionDecision.DENY)) {
            return new GateResult(GateDecision.DENY, profileRule, "profile");
        }
        if (hasDecision(globalRule, PermissionDecision.DENY)) {
            return new GateResult(GateDecision.DENY, globalRule, "global");
        }
        if (hasDecision(profileRule, PermissionDecision.ASK)) {
            return new GateResult(GateDecision.ASK, profileRule, "profile");
        }
        if (hasDecision(globalRule, PermissionDecision.ASK)) {
            return new GateResult(GateDecision.ASK, globalRule, "global");
        }
        return profileRule != null
                ? new GateResult(GateDecision.ALLOW, profileRule, "profile")
                : new GateResult(GateDecision.ALLOW, globalRule, "global");
    }

    private static boolean hasDecision(PermissionRule rule, PermissionDecision decision) {
        return rule != null && rule.getDecision() == decision;
    }
}
