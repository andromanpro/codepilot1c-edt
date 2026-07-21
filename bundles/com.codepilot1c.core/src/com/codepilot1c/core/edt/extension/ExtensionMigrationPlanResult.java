package com.codepilot1c.core.edt.extension;

import java.util.List;

/**
 * Dry-run/apply plan emitted by native extension migration planner.
 */
public record ExtensionMigrationPlanResult(
        String sourceProject,
        String extensionProject,
        boolean dryRun,
        int operationCount,
        List<MigrationPlanOperation> operations,
        List<String> warnings
) {
    public record MigrationPlanOperation(
            int order,
            String action,
            String sourceFqn,
            String targetFqn,
            String kind,
            String status,
            List<String> covers,
            List<String> skipped
    ) {
    }
}
