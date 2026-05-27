package com.codepilot1c.core.edt.metadata;

import java.util.List;

/**
 * Resolves the language-code key under which to write a synonym value when
 * creating or updating BM-API metadata objects.
 *
 * <p>Historically {@code setCommonProperties} hard-coded {@code "ru"} for
 * the synonym map.  English-only configurations (and any project whose
 * default language is not Russian) therefore had to be patched after every
 * BM-API call to flip {@code <key>ru</key>} to {@code <key>en</key>}.
 * The resolver consults the Configuration's default-language and full
 * language list so a freshly-created object lands with the correct locale
 * the first time.</p>
 *
 * <p>The pure-data {@link #resolve(String, List)} entry point keeps the
 * logic unit-testable without an Eclipse OSGi runtime.</p>
 */
public final class BmSynonymLocaleResolver {

    /**
     * Fallback used when no Configuration language is available.  Matches
     * the historical behaviour so existing fixtures continue to work for
     * Russian-only projects.
     */
    public static final String FALLBACK_LANGUAGE_CODE = "ru"; //$NON-NLS-1$

    private BmSynonymLocaleResolver() {
    }

    /**
     * Pick the synonym key from raw inputs.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>{@code defaultLanguageCode} if non-blank;</li>
     *   <li>first non-blank entry of {@code configurationLanguageCodes};</li>
     *   <li>{@link #FALLBACK_LANGUAGE_CODE} ({@code "ru"}).</li>
     * </ol>
     *
     * <p>This deterministic chain matches what an EDT user sees in the
     * Configuration editor: the platform itself uses the default-language
     * for new synonyms, and a project that has only one configured language
     * effectively makes that language the default even when no explicit
     * default is set.</p>
     *
     * @param defaultLanguageCode {@code Configuration.defaultLanguage.languageCode}, may be null/blank
     * @param configurationLanguageCodes ordered codes from {@code Configuration.languages}, may be empty
     * @return non-null, non-blank language code
     */
    public static String resolve(String defaultLanguageCode, List<String> configurationLanguageCodes) {
        if (isUsable(defaultLanguageCode)) {
            return defaultLanguageCode.trim();
        }
        if (configurationLanguageCodes != null) {
            for (String code : configurationLanguageCodes) {
                if (isUsable(code)) {
                    return code.trim();
                }
            }
        }
        return FALLBACK_LANGUAGE_CODE;
    }

    private static boolean isUsable(String code) {
        return code != null && !code.isBlank();
    }
}
