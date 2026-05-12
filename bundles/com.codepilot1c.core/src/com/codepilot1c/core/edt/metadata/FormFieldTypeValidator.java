package com.codepilot1c.core.edt.metadata;

import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java helpers for validating {@code field_type} values supplied to
 * {@code mutate_form_model.add_field} before they reach the BM API.
 *
 * <p>Catches one common gotcha: the agent often sends
 * {@code field_type:"CHECK_BOX_FIELD"} for Boolean cells inside a Table.
 * The 1C platform rejects this with a cryptic {@code SU107} ("Illegal
 * extension type for field type 'CheckBoxField'") because Table cells
 * render Boolean automatically through {@code INPUT_FIELD}.</p>
 *
 * <p>Surfacing the constraint here gives the agent an actionable error
 * <em>before</em> the BM transaction runs.  No behavioural change for
 * field types outside the deny-list — those continue to round-trip into
 * the platform unchanged.</p>
 */
public final class FormFieldTypeValidator {

    /**
     * Field types that the 1C platform refuses inside a Table.  Boolean
     * columns are rendered via {@code INPUT_FIELD} (the platform draws
     * a checkmark for Boolean data automatically); the dedicated
     * {@code CHECK_BOX_FIELD}, {@code RADIO_BUTTON_FIELD},
     * {@code PROGRESS_BAR_FIELD} and {@code TRACK_BAR_FIELD} extension
     * types are flagged by SU107 when used inside a Table.
     */
    private static final Set<String> TABLE_INCOMPATIBLE_FIELD_TYPES = Set.of(
            "CHECKBOXFIELD", //$NON-NLS-1$
            "RADIOBUTTONFIELD", //$NON-NLS-1$
            "PROGRESSBARFIELD", //$NON-NLS-1$
            "TRACKBARFIELD"); //$NON-NLS-1$

    private FormFieldTypeValidator() {
    }

    /**
     * Returns {@code true} if the given field-type string is incompatible
     * with a Table parent (rejected by SU107 at platform level).
     *
     * @param fieldType raw {@code field_type} string from the agent payload
     *                  (case-insensitive, {@code _}/{@code -}/space ignored;
     *                  may be null/blank)
     * @return {@code true} if the platform would reject this field type
     *         inside a Table parent
     */
    public static boolean isIncompatibleWithTableParent(String fieldType) {
        if (fieldType == null) {
            return false;
        }
        String normalized = fieldType
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toUpperCase(Locale.ROOT);
        return TABLE_INCOMPATIBLE_FIELD_TYPES.contains(normalized);
    }

    /**
     * Builds the canonical "use INPUT_FIELD instead" error message.
     *
     * @param rawFieldType the raw field-type string the caller supplied
     *                     (echoed back verbatim)
     * @param fieldName field name from the operation, may be null
     * @return human-readable error message
     */
    public static String tableIncompatibleFieldTypeMessage(String rawFieldType, String fieldName) {
        StringBuilder sb = new StringBuilder();
        sb.append("field_type '").append(rawFieldType).append("' is not allowed inside a Table parent"); //$NON-NLS-1$ //$NON-NLS-2$
        if (fieldName != null && !fieldName.isBlank()) {
            sb.append(" (field name='").append(fieldName).append("')"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append(": the 1C platform rejects it with SU107 'Illegal extension type for field type'."); //$NON-NLS-1$
        sb.append(" Use field_type=\"INPUT_FIELD\" — Boolean cells render as a checkmark automatically,"); //$NON-NLS-1$
        sb.append(" choice cells render as a dropdown, and so on."); //$NON-NLS-1$
        return sb.toString();
    }
}
