package com.codepilot1c.core.edt.extension;

import java.util.List;

/**
 * Result of adopting metadata object into extension project.
 *
 * <p>{@code attachedDependencies} lists everything EDT attached besides the requested
 * object, {@code removedDependencies} lists what was pruned afterwards. Both are always
 * reported, so a caller that keeps the default mode still sees how far the adoption
 * spread instead of discovering it months later.</p>
 */
public record ExtensionAdoptObjectResult(
        String extensionProject,
        String baseProject,
        String sourceObjectFqn,
        String adoptedObjectFqn,
        String kind,
        String name,
        boolean alreadyAdopted,
        boolean updated,
        String dependenciesMode,
        List<String> attachedDependencies,
        List<String> removedDependencies,
        List<String> failedRemovals,
        String warning
) {
}
