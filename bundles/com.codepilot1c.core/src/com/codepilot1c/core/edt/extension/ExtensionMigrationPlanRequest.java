package com.codepilot1c.core.edt.extension;

import java.util.List;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Request for dry-run-first native extension migration planning.
 */
public record ExtensionMigrationPlanRequest(
        String sourceProject,
        String extensionProject,
        List<String> sourceFqns,
        boolean apply
) {
    public void validate() {
        if (sourceProject == null || sourceProject.isBlank()) {
            throw new MetadataOperationException(MetadataOperationCode.PROJECT_NOT_FOUND,
                    "source_project is required", false); //$NON-NLS-1$
        }
        if (extensionProject == null || extensionProject.isBlank()) {
            throw new MetadataOperationException(MetadataOperationCode.PROJECT_NOT_FOUND,
                    "extension_project is required", false); //$NON-NLS-1$
        }
        if (sourceProject.equals(extensionProject)) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "source_project and extension_project must be different", false); //$NON-NLS-1$
        }
        if (sourceFqns == null || sourceFqns.isEmpty()) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "source_fqns must contain at least one FQN", false); //$NON-NLS-1$
        }
        for (String sourceFqn : sourceFqns) {
            if (sourceFqn == null || sourceFqn.isBlank() || !sourceFqn.contains(".")) { //$NON-NLS-1$
                throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                        "source_fqns must use Kind.Name form: " + sourceFqn, false); //$NON-NLS-1$
            }
        }
    }
}
