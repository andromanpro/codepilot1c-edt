package com.codepilot1c.core.edt.extension;

import java.util.List;
import java.util.Locale;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Request for adopting a base configuration object into extension project.
 *
 * <p>EDT adopts an object together with everything its adopter participants consider
 * related, and the platform API exposes no switch for that. Adopting a single managed
 * form may therefore attach dozens of catalogs, common forms, pictures and style items.
 * Configurator asks the user what to do; this request carries the same choice via
 * {@code dependenciesMode}: keep everything (default, historical behaviour), keep nothing
 * but the requested object, or keep an explicit white list.</p>
 */
public record ExtensionAdoptObjectRequest(
        String baseProjectName,
        String extensionProjectName,
        String sourceObjectFqn,
        Boolean updateIfExists,
        String dependenciesMode,
        List<String> keepDependencies
) {
    /** Keep every object EDT attached (historical behaviour, safe default). */
    public static final String DEPENDENCIES_ALL = "all"; //$NON-NLS-1$
    /** Remove everything EDT attached except the requested object. */
    public static final String DEPENDENCIES_NONE = "none"; //$NON-NLS-1$
    /** Keep only dependencies listed in {@link #keepDependencies()}. */
    public static final String DEPENDENCIES_LIST = "list"; //$NON-NLS-1$

    public ExtensionAdoptObjectRequest(
            String baseProjectName,
            String extensionProjectName,
            String sourceObjectFqn,
            Boolean updateIfExists
    ) {
        this(baseProjectName, extensionProjectName, sourceObjectFqn, updateIfExists, null, null);
    }

    public void validate() {
        if (extensionProjectName == null || extensionProjectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EXTENSION_PROJECT_NOT_FOUND,
                    "extension_project is required", false); //$NON-NLS-1$
        }
        if (sourceObjectFqn == null || sourceObjectFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "source_object_fqn is required", false); //$NON-NLS-1$
        }
        String mode = normalizedDependenciesMode();
        if (!DEPENDENCIES_ALL.equals(mode)
                && !DEPENDENCIES_NONE.equals(mode)
                && !DEPENDENCIES_LIST.equals(mode)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "adopt_dependencies must be \"all\", \"none\" or an array of FQNs", false); //$NON-NLS-1$
        }
        if (DEPENDENCIES_LIST.equals(mode) && normalizedKeepDependencies().isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "adopt_dependencies list is empty: pass \"none\" to drop every dependency", false); //$NON-NLS-1$
        }
    }

    public String normalizedBaseProjectName() {
        if (baseProjectName == null || baseProjectName.isBlank()) {
            return null;
        }
        return baseProjectName.trim();
    }

    public String normalizedExtensionProjectName() {
        return extensionProjectName.trim();
    }

    public String normalizedSourceObjectFqn() {
        return sourceObjectFqn.trim();
    }

    public boolean shouldUpdateIfExists() {
        return updateIfExists != null && updateIfExists.booleanValue();
    }

    /** Defaults to {@link #DEPENDENCIES_ALL}: never silently changes what callers used to get. */
    public String normalizedDependenciesMode() {
        if (dependenciesMode == null || dependenciesMode.isBlank()) {
            return DEPENDENCIES_ALL;
        }
        return dependenciesMode.trim().toLowerCase(Locale.ROOT);
    }

    public List<String> normalizedKeepDependencies() {
        if (keepDependencies == null) {
            return List.of();
        }
        return keepDependencies.stream()
                .filter(fqn -> fqn != null && !fqn.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public boolean prunesDependencies() {
        return !DEPENDENCIES_ALL.equals(normalizedDependenciesMode());
    }
}
