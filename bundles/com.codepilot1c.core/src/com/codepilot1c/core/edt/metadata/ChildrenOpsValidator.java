package com.codepilot1c.core.edt.metadata;

import java.util.Locale;
import java.util.Set;

/**
 * Pure-Java validation helpers for {@code update_metadata.children_ops}.
 *
 * <p>Extracted from {@link EdtMetadataService} so the upstream-facing rules
 * can be unit-tested without an Eclipse OSGi runtime.</p>
 */
public final class ChildrenOpsValidator {

    /**
     * Op-aliases that callers commonly send when they want to <em>create</em>
     * a new child object.  {@code children_ops} only operates on already-
     * existing children, so we reject these explicitly and route the agent
     * to {@code add_metadata_child}.
     *
     * <p>Keys here are the post-{@link #normalizeOpToken(String)} form
     * ({@code "_"}, {@code "-"}, {@code " "} stripped, lower-cased).</p>
     */
    private static final Set<String> CREATE_INTENT_OPS = Set.of(
            "add", //$NON-NLS-1$
            "addchild", //$NON-NLS-1$
            "create", //$NON-NLS-1$
            "createchild", //$NON-NLS-1$
            "new", //$NON-NLS-1$
            "newchild", //$NON-NLS-1$
            "insert"); //$NON-NLS-1$

    private ChildrenOpsValidator() {
    }

    /**
     * Normalizes an {@code op} string the same way {@link EdtMetadataService}
     * does internally: strip {@code _ - <space>}, lower-case (root locale).
     *
     * @param value raw op string from the JSON payload (may be null)
     * @return normalized token, never null
     */
    public static String normalizeOpToken(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Returns {@code true} if {@code normalizedOp} expresses the intent to
     * create a brand-new child object.  Callers should reject these with a
     * pointer to the {@code add_metadata_child} tool — {@code children_ops}
     * cannot create objects.
     *
     * @param normalizedOp output of {@link #normalizeOpToken(String)}
     * @return {@code true} if the op represents a create-child intent
     */
    public static boolean isCreateChildIntent(String normalizedOp) {
        return normalizedOp != null && CREATE_INTENT_OPS.contains(normalizedOp);
    }

    /**
     * Builds the canonical "use add_metadata_child instead" error message.
     * Keeping the wording in one place makes it easier to assert on in
     * tests and to keep agent-facing language consistent.
     *
     * @param rawOp the original op string the caller supplied (echoed back)
     * @return human-readable error message
     */
    public static String createChildIntentRejectionMessage(String rawOp) {
        return "children_ops op '" + rawOp //$NON-NLS-1$
                + "' is not supported: children_ops only operates on existing children." //$NON-NLS-1$
                + " To create a new child use the add_metadata_child tool" //$NON-NLS-1$
                + " (parent_fqn=<target>, child_kind=<Attribute|TabularSection|EnumValue|...>)."; //$NON-NLS-1$
    }
}
