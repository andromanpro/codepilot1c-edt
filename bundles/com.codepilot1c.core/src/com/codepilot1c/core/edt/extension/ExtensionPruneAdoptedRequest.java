package com.codepilot1c.core.edt.extension;

import java.util.List;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Request for removing adopted objects that the extension does not actually use.
 *
 * <p>Adoption in EDT attaches related objects silently, so an extension may accumulate
 * dozens of stub objects nobody references. This request finds them and (optionally)
 * removes them. {@code dryRun} defaults to {@code true}: the caller sees the candidate
 * list first and only then decides.</p>
 */
public record ExtensionPruneAdoptedRequest(
        String baseProjectName,
        String extensionProjectName,
        List<String> keep,
        List<String> remove,
        Boolean dryRun
) {
    public void validate() {
        if (extensionProjectName == null || extensionProjectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EXTENSION_PROJECT_NOT_FOUND,
                    "extension_project is required", false); //$NON-NLS-1$
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

    public List<String> normalizedKeep() {
        return normalizeList(keep);
    }

    public List<String> normalizedRemove() {
        return normalizeList(remove);
    }

    /** Safe by default: nothing is deleted unless the caller explicitly asks. */
    public boolean isDryRun() {
        return dryRun == null || dryRun.booleanValue();
    }

    private static List<String> normalizeList(List<String> source) {
        if (source == null) {
            return List.of();
        }
        return source.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
