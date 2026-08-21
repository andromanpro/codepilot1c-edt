package com.codepilot1c.core.tools.diagnostics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure-Java contract describing what each {@code edt_diagnostics}
 * sub-command requires from its caller.
 *
 * <p>The composite tool advertised {@code command} as the only required
 * field of its JSON schema, but every delegate had its own per-command
 * required fields ({@code project}, {@code project_name},
 * {@code tool_result}).  Agents discovered the constraint only after the
 * call failed inside the delegate with
 * {@code [INVALID_ARGUMENT] project is required} — a confusing error
 * because the parent schema never mentioned the field.</p>
 *
 * <p>This helper centralizes the contract so the parent tool can:</p>
 * <ul>
 *   <li>fail fast with a clear "command X requires field Y" message,</li>
 *   <li>render the per-command requirements in the tool description,</li>
 *   <li>alias {@code project} → {@code project_name} for the workspace
 *       delegates so callers don't need to remember which name applies.</li>
 * </ul>
 */
public final class EdtDiagnosticsCommandContract {

    /**
     * Field-naming alias map: workspace delegates historically use
     * {@code project_name}, while metadata delegates use the shorter
     * {@code project}.  When a caller supplies one, populate the other.
     */
    private static final Map<String, String> PROJECT_FIELD_ALIASES = Map.of(
            "project", "project_name", //$NON-NLS-1$ //$NON-NLS-2$
            "project_name", "project"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Per-command required fields. Order is significant for the message. */
    private static final Map<String, List<String>> REQUIRED_FIELDS_BY_COMMAND;
    static {
        Map<String, List<String>> table = new LinkedHashMap<>();
        table.put("metadata_smoke", List.of("project")); //$NON-NLS-1$ //$NON-NLS-2$
        table.put("trace_export", List.of("project")); //$NON-NLS-1$ //$NON-NLS-2$
        table.put("analyze_error", List.of("tool_result")); //$NON-NLS-1$ //$NON-NLS-2$
        table.put("update_infobase", List.of("project_name")); //$NON-NLS-1$ //$NON-NLS-2$
        table.put("launch_app", List.of("project_name")); //$NON-NLS-1$ //$NON-NLS-2$
        table.put("uuid_check", List.of("project")); //$NON-NLS-1$ //$NON-NLS-2$
        REQUIRED_FIELDS_BY_COMMAND = Map.copyOf(table);
    }

    private EdtDiagnosticsCommandContract() {
    }

    /**
     * Required fields for {@code command} (empty if the command is unknown).
     */
    public static List<String> requiredFields(String command) {
        if (command == null) {
            return List.of();
        }
        return REQUIRED_FIELDS_BY_COMMAND.getOrDefault(command, List.of());
    }

    /**
     * Find the first required-field that is missing from {@code params}.
     * A field is considered present if its value is non-null and (for
     * strings) non-blank.
     *
     * @param command sub-command name; if unknown, no fields are checked
     * @param params caller-supplied parameter map
     * @return missing field name, or {@code null} if all required fields
     *         are present
     */
    public static String findFirstMissingRequired(String command, Map<String, Object> params) {
        if (command == null) {
            return null;
        }
        Map<String, Object> view = params == null ? Map.of() : params;
        for (String required : requiredFields(command)) {
            if (!isPresent(view, required)) {
                return required;
            }
        }
        return null;
    }

    /**
     * Build the agent-facing error message for a missing required field.
     * Mentions the alias so the caller knows both spellings work.
     */
    public static String missingRequiredFieldMessage(String command, String missingField) {
        StringBuilder sb = new StringBuilder();
        sb.append("edt_diagnostics command '").append(command).append("' requires '") //$NON-NLS-1$ //$NON-NLS-2$
                .append(missingField).append("'"); //$NON-NLS-1$
        String alias = PROJECT_FIELD_ALIASES.get(missingField);
        if (alias != null) {
            sb.append(" (or alias '").append(alias).append("')"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append(" — supplied parameters did not include it. Pass {\"command\":\"") //$NON-NLS-1$
                .append(command).append("\", \"").append(missingField).append("\":\"...\"}."); //$NON-NLS-1$ //$NON-NLS-2$
        return sb.toString();
    }

    /**
     * Mirror caller-supplied {@code project}/{@code project_name} into the
     * partner key so each delegate sees the field-name it expects.
     * Returns a NEW map; the input is not mutated.
     */
    public static Map<String, Object> applyProjectFieldAliases(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return params;
        }
        Map<String, Object> aliased = new LinkedHashMap<>(params);
        for (Map.Entry<String, String> alias : PROJECT_FIELD_ALIASES.entrySet()) {
            String source = alias.getKey();
            String target = alias.getValue();
            if (isPresent(aliased, source) && !isPresent(aliased, target)) {
                aliased.put(target, aliased.get(source));
            }
        }
        return aliased;
    }

    /**
     * Pretty-print the contract as a one-line-per-command list, suitable
     * for embedding in the tool description.
     */
    public static String describeRequirements() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : REQUIRED_FIELDS_BY_COMMAND.entrySet()) {
            if (!first) {
                sb.append("; "); //$NON-NLS-1$
            }
            first = false;
            sb.append(entry.getKey()).append(" -> "); //$NON-NLS-1$
            sb.append(String.join(",", entry.getValue())); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static boolean isPresent(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isBlank();
        }
        return !Objects.toString(value, "").isBlank(); //$NON-NLS-1$
    }
}
