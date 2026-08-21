package com.codepilot1c.core.edt.metadata;

/**
 * Request for metadata delete operation.
 */
public record DeleteMetadataRequest(
        String projectName,
        String targetFqn,
        boolean recursive,
        boolean force,
        boolean allowSupportedObjectEdit
) {
    public DeleteMetadataRequest(String projectName, String targetFqn, boolean recursive) {
        this(projectName, targetFqn, recursive, false, false);
    }

    public DeleteMetadataRequest(String projectName, String targetFqn, boolean recursive, boolean force) {
        this(projectName, targetFqn, recursive, force, false);
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
    }
}
