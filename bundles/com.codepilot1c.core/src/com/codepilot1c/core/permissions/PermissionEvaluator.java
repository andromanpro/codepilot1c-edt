/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.permissions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Чистый эвалюатор permission-правил без зависимостей от Eclipse Platform.
 */
public final class PermissionEvaluator {

    /** Ресурс по умолчанию, когда аргументы не содержат ресурсного ключа. */
    public static final String ANY_RESOURCE = "*";

    private static final String[] RESOURCE_KEYS = {
            "path", "file", "filePath", "command", "resource"
    };

    /**
     * Дополнительные объектные идентификаторы только для strict agent gate.
     * Порядок задаёт приоритет и намеренно идёт после legacy-ключей, чтобы
     * сохранить {@code command} как ресурс для manage-инструментов.
     */
    private static final String[] GATE_ONLY_RESOURCE_KEYS = {
            "target_fqn",
            "parent_fqn",
            "form_fqn",
            "owner_fqn",
            "object_fqn",
            "template_fqn",
            "role",
            "repo_path"
    };

    private static final String[] PATH_RESOURCE_KEYS = {
            "path", "file", "filePath", "repo_path"
    };

    private record ResolvedResource(String key, String value) {
    }

    private PermissionEvaluator() {
    }

    /**
     * Извлекает raw-ресурс из аргументов инструмента без нормализации.
     * Это неизменяемый legacy-контракт {@link PermissionManager} и MCP Host:
     * список ключей нельзя расширять. Strict agent gate использует
     * {@link #gateResourceOf(Map)}.
     *
     * @param arguments аргументы инструмента
     * @return значение первого известного ресурсного ключа или {@link #ANY_RESOURCE}
     */
    public static String resourceOf(Map<String, Object> arguments) {
        return resolve(arguments, false).value();
    }

    /**
     * Извлекает raw-ресурс для strict agent gate. Legacy-ключи всегда имеют
     * приоритет перед gate-only ключами. Возвращаемое значение предназначено
     * для diagnostics/payload; правила сопоставляются с нормализованным
     * ресурсом и его кандидатами.
     *
     * <p>{@code create_metadata} не имеет объектного ключа и остаётся со
     * значением {@link #ANY_RESOURCE}. {@code git_mutate} без
     * {@code repo_path} также остаётся со значением {@link #ANY_RESOURCE}:
     * {@code operation}, {@code project_name} и {@code project_path} намеренно
     * не являются resource keys.</p>
     *
     * @param arguments аргументы инструмента
     * @return значение первого strict resource key или {@link #ANY_RESOURCE}
     */
    public static String gateResourceOf(Map<String, Object> arguments) {
        return resolve(arguments, true).value();
    }

    /**
     * Возвращает подходящее правило с наибольшим приоритетом.
     * При равном приоритете сохраняется порядок правил во входном списке.
     * Matching выполняется напрямую через {@link PermissionRule#matches(String)}
     * без нормализации или расширения кандидатов.
     *
     * @param rules правила для проверки
     * @param toolName имя инструмента
     * @param resource ресурс инструмента
     * @return выигравшее правило или empty, если совпадений нет
     */
    public static Optional<PermissionRule> firstMatch(
            List<PermissionRule> rules, String toolName, String resource) {
        if (rules == null || rules.isEmpty()) {
            return Optional.empty();
        }

        List<PermissionRule> matchingRules = new ArrayList<>();
        for (PermissionRule rule : rules) {
            if (rule != null && rule.matchesTool(toolName) && rule.matches(resource)) {
                matchingRules.add(rule);
            }
        }
        if (matchingRules.isEmpty()) {
            return Optional.empty();
        }

        matchingRules.sort(Comparator.comparingInt(PermissionRule::getPriority).reversed());
        return Optional.of(matchingRules.get(0));
    }

    /**
     * Возвращает самое строгое совпавшее правило: DENY &gt; ASK &gt; ALLOW.
     * Внутри одного решения учитываются приоритет и стабильный порядок списка.
     * Этот метод предназначен для agent-profile/global composition; legacy
     * {@link PermissionManager} сохраняет priority-first семантику через
     * {@link #firstMatch(List, String, String)}.
     *
     * @param rules правила для проверки
     * @param toolName имя инструмента
     * @param resource ресурс инструмента
     * @return самое строгое правило или empty, если совпадений нет
     */
    public static Optional<PermissionRule> strictestMatch(
            List<PermissionRule> rules, String toolName, String resource) {
        if (rules == null || rules.isEmpty()) {
            return Optional.empty();
        }

        List<PermissionRule> matchingRules = new ArrayList<>();
        for (PermissionRule rule : rules) {
            if (matchesStrict(rule, toolName, resource)) {
                matchingRules.add(rule);
            }
        }
        matchingRules.sort(Comparator
                .comparingInt((PermissionRule rule) -> strictness(rule.getDecision()))
                .reversed()
                .thenComparing(Comparator.comparingInt(PermissionRule::getPriority).reversed()));
        return matchingRules.stream().findFirst();
    }

    /**
     * Extracts and normalizes path-like resources for the strict agent gate.
     * Legacy callers must continue to use {@link #resourceOf(Map)}.
     */
    static String normalizedResourceOf(Map<String, Object> arguments) {
        ResolvedResource resource = resolve(arguments, true);
        return isPathResourceKey(resource.key())
                ? normalizePathSeparators(resource.value())
                : resource.value();
    }

    private static ResolvedResource resolve(
            Map<String, Object> arguments, boolean includeGateKeys) {
        ResolvedResource legacy = resolveFirst(arguments, RESOURCE_KEYS);
        if (legacy != null) {
            return legacy;
        }
        if (includeGateKeys) {
            ResolvedResource gateOnly = resolveFirst(arguments, GATE_ONLY_RESOURCE_KEYS);
            if (gateOnly != null) {
                return gateOnly;
            }
        }
        return new ResolvedResource(null, ANY_RESOURCE);
    }

    private static ResolvedResource resolveFirst(
            Map<String, Object> arguments, String[] keys) {
        if (arguments == null) {
            return null;
        }
        for (String key : keys) {
            Object value = arguments.get(key);
            if (value != null) {
                return new ResolvedResource(key, String.valueOf(value));
            }
        }
        return null;
    }

    private static boolean isPathResourceKey(String selectedKey) {
        if (selectedKey == null) {
            return false;
        }
        for (String key : PATH_RESOURCE_KEYS) {
            if (key.equals(selectedKey)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesStrict(PermissionRule rule, String toolName, String resource) {
        if (rule == null || !rule.matchesTool(toolName)) {
            return false;
        }
        for (String candidate : resourceCandidates(resource)) {
            if (rule.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> resourceCandidates(String resource) {
        Set<String> candidates = new LinkedHashSet<>();
        if (resource == null) {
            candidates.add(null);
            return candidates;
        }

        String normalized = resource;
        candidates.add(normalized);
        int extensionStart = normalized.lastIndexOf('.');
        if (extensionStart >= 0 && extensionStart > normalized.lastIndexOf('/')) {
            candidates.add(normalized.substring(0, extensionStart)
                    + normalized.substring(extensionStart).toLowerCase(Locale.ROOT));
        }
        if (!normalized.contains("/") && !ANY_RESOURCE.equals(normalized)) {
            for (String candidate : List.copyOf(candidates)) {
                candidates.add("./" + candidate);
            }
        }
        return candidates;
    }

    private static String normalizePathSeparators(String resource) {
        return resource.replace('\\', '/');
    }

    private static int strictness(PermissionDecision decision) {
        return switch (decision) {
            case DENY -> 3;
            case ASK -> 2;
            case ALLOW -> 1;
        };
    }
}
