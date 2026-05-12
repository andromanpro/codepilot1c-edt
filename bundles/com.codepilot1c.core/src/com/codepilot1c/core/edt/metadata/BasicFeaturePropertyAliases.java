package com.codepilot1c.core.edt.metadata;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure-Java alias maps for the enum-typed properties an agent commonly
 * supplies for a freshly-created BasicFeature ({@code Catalog.Attribute},
 * {@code Document.Attribute}, {@code TabularSection.Attribute}, ...).
 *
 * <p>Each {@code resolveX(...)} method returns the EMF literal name
 * (UPPER_SNAKE_CASE) corresponding to the agent-friendly input.  Callers
 * feed the literal to the matching EMF enum's {@code valueOf} (or
 * {@code get(name)}) to obtain the actual constant.</p>
 *
 * <p>Aliases are case- and separator-insensitive: "ShowError",
 * "show_error", "SHOW-ERROR", "SHOW ERROR" all collapse to
 * {@code SHOW_ERROR}.  Boolean-shaped aliases are recognized too
 * ("true"/"yes" → the affirmative literal, "false"/"no" → the negative).</p>
 */
public final class BasicFeaturePropertyAliases {

    /** {@code BasicFeature.fillChecking} (FillChecking enum literals). */
    private static final Map<String, String> FILL_CHECKING = aliases(builder -> {
        builder.add("DONT_CHECK", "dontcheck", "dont_check", "none", "no", "false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        builder.add("SHOW_ERROR", "showerror", "show_error", "error", "required", "yes", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
    });

    /** {@code DataHistorySupport.dataHistory} (DataHistoryUse enum literals). */
    private static final Map<String, String> DATA_HISTORY = aliases(builder -> {
        builder.add("USE", "use", "yes", "true", "on"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        builder.add("DONT_USE", "dontuse", "dont_use", "none", "no", "false", "off"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
    });

    /** {@code DbObjectAttribute.fullTextSearch} (FullTextSearchUsing enum literals). */
    private static final Map<String, String> FULL_TEXT_SEARCH = aliases(builder -> {
        builder.add("USE", "use", "yes", "true", "on"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        builder.add("DONT_USE", "dontuse", "dont_use", "none", "no", "false", "off"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
    });

    /** {@code DbObjectAttribute.indexing} (Indexing enum literals). */
    private static final Map<String, String> INDEXING = aliases(builder -> {
        builder.add("DONT_INDEX", "dontindex", "dont_index", "none", "no", "false"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        builder.add("INDEX", "index", "yes", "true"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        builder.add("INDEX_WITH_ADDITIONAL_ORDER", "indexwithadditionalorder", //$NON-NLS-1$ //$NON-NLS-2$
                "withadditionalorder", "additionalorder"); //$NON-NLS-1$ //$NON-NLS-2$
    });

    private BasicFeaturePropertyAliases() {
    }

    /**
     * Resolve a {@code fillChecking} alias to the matching
     * {@code FillChecking} EMF literal name (UPPER_SNAKE_CASE).
     */
    public static Optional<String> resolveFillChecking(String raw) {
        return resolve(FILL_CHECKING, raw);
    }

    /**
     * Resolve a {@code dataHistory} alias to the matching
     * {@code DataHistoryUse} EMF literal name.
     */
    public static Optional<String> resolveDataHistory(String raw) {
        return resolve(DATA_HISTORY, raw);
    }

    /**
     * Resolve a {@code fullTextSearch} alias to the matching
     * {@code FullTextSearchUsing} EMF literal name.
     */
    public static Optional<String> resolveFullTextSearch(String raw) {
        return resolve(FULL_TEXT_SEARCH, raw);
    }

    /** Resolve an {@code indexing} alias to the matching {@code Indexing} literal. */
    public static Optional<String> resolveIndexing(String raw) {
        return resolve(INDEXING, raw);
    }

    private static Optional<String> resolve(Map<String, String> table, String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = normalize(raw);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(table.get(normalized));
    }

    private static String normalize(String value) {
        return value
                .replace("_", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("-", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(" ", "") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("'", "") //$NON-NLS-1$ //$NON-NLS-2$
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    private interface AliasBuilder {
        /**
         * Register {@code canonical} (the EMF literal) and zero or more
         * {@code aliases} that should resolve to it.  All entries are
         * normalized via {@link #normalize(String)} before being stored.
         */
        void add(String canonical, String... aliases);
    }

    private static Map<String, String> aliases(java.util.function.Consumer<AliasBuilder> populator) {
        Map<String, String> map = new HashMap<>();
        populator.accept((canonical, aliases) -> {
            String normalizedCanonical = normalize(canonical);
            map.put(normalizedCanonical, canonical);
            for (String alias : aliases) {
                if (alias == null) {
                    continue;
                }
                String normalizedAlias = normalize(alias);
                if (normalizedAlias.isEmpty()) {
                    continue;
                }
                // Last write wins — later entries override earlier ones if the
                // same alias appears twice (which it should not, but the build
                // is forgiving so a typo doesn't produce a class-init crash).
                map.put(normalizedAlias, canonical);
            }
        });
        return Map.copyOf(map);
    }
}
