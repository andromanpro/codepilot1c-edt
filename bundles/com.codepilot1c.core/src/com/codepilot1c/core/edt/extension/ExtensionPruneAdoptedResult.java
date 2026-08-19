package com.codepilot1c.core.edt.extension;

import java.util.List;

/**
 * Result of pruning unused adopted objects from an extension project.
 *
 * <p>{@code candidates} are adopted objects with no textual reference anywhere in the
 * extension sources; {@code removed} is what was actually deleted (empty on a dry run).
 * {@code referenced} is reported too, so the caller can see the search was not vacuous.</p>
 */
public record ExtensionPruneAdoptedResult(
        String extensionProject,
        String baseProject,
        boolean dryRun,
        int adoptedTotal,
        List<String> candidates,
        List<String> referenced,
        List<String> keptByRequest,
        List<String> removed,
        List<String> failedRemovals
) {
}
