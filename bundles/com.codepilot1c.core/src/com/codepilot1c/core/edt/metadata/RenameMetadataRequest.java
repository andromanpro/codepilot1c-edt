package com.codepilot1c.core.edt.metadata;

/**
 * Request for metadata rename refactoring (EDT-grade rename with reference updates).
 */
public record RenameMetadataRequest(
        String projectName,
        String targetFqn,
        String newName,
        String predefinedItem
) {
    public RenameMetadataRequest(String projectName, String targetFqn, String newName) {
        this(projectName, targetFqn, newName, null);
    }

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (targetFqn == null || targetFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "targetFqn is required", false); //$NON-NLS-1$
        }
        if (newName == null || newName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "newName is required", false); //$NON-NLS-1$
        }
        if (!newName.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) { //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "newName is not a valid metadata identifier: " + newName, //$NON-NLS-1$
                    false);
        }
    }
}
