package com.codepilot1c.core.edt.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.codepilot1c.core.edt.extension.ExtensionMigrationPlanResult.MigrationPlanOperation;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Builds an ordered dry-run-first native extension migration plan.
 */
public class ExtensionMigrationPlanner {

    private static final Set<String> SUPPORTED_TOP_LEVEL_KINDS = Set.of(
            "InformationRegister", //$NON-NLS-1$
            "Catalog", //$NON-NLS-1$
            "HTTPService", //$NON-NLS-1$
            "CommonCommand", //$NON-NLS-1$
            "ScheduledJob", //$NON-NLS-1$
            "Bot", //$NON-NLS-1$
            "Role" //$NON-NLS-1$
    );

    public ExtensionMigrationPlanResult plan(ExtensionMigrationPlanRequest request) {
        request.validate();
        if (request.apply()) {
            throw new MetadataOperationException(MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "apply mode is gated: run dry_run first, validate emitted operations, then execute primitives with validation tokens", //$NON-NLS-1$
                    false);
        }
        List<MigrationPlanOperation> operations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int order = 1;
        for (String sourceFqn : request.sourceFqns()) {
            ParsedFqn parsed = parseTopLevelFqn(sourceFqn);
            String targetName = effectiveExtensionName(request.extensionProject(), parsed.name());
            String targetFqn = parsed.kind().getFqnPrefix() + "." + targetName; //$NON-NLS-1$
            List<String> covers = coverageForKind(parsed.kind());
            List<String> skipped = skippedForKind(parsed.kind());
            String status = SUPPORTED_TOP_LEVEL_KINDS.contains(parsed.kind().getFqnPrefix())
                    ? "planned" : "unsupported_kind"; //$NON-NLS-1$ //$NON-NLS-2$
            if (!"planned".equals(status)) { //$NON-NLS-1$
                warnings.add("Unsupported top-level kind for native clone plan: " + parsed.kind().getFqnPrefix()); //$NON-NLS-1$
            }
            operations.add(new MigrationPlanOperation(order++, "create_top_level", sourceFqn, targetFqn, //$NON-NLS-1$
                    parsed.kind().getFqnPrefix(), status, covers, skipped));
            if (covers.contains("modules")) { //$NON-NLS-1$
                operations.add(new MigrationPlanOperation(order++, "copy_modules", sourceFqn, targetFqn, //$NON-NLS-1$
                        parsed.kind().getFqnPrefix(), status, List.of("ObjectModule", "ManagerModule", "CommandModule", "Module"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        List.of()));
            }
            if (covers.contains("forms")) { //$NON-NLS-1$
                operations.add(new MigrationPlanOperation(order++, "copy_forms", sourceFqn, targetFqn, //$NON-NLS-1$
                        parsed.kind().getFqnPrefix(), status, List.of("forms"), List.of())); //$NON-NLS-1$
            }
            operations.add(new MigrationPlanOperation(order++, "rewrite_references", sourceFqn, targetFqn, //$NON-NLS-1$
                    parsed.kind().getFqnPrefix(), "dry_run_only", List.of("metadata_references"), //$NON-NLS-1$ //$NON-NLS-2$
                    List.of("arbitrary_bsl_semantic_rewrite"))); //$NON-NLS-1$
        }
        return new ExtensionMigrationPlanResult(request.sourceProject(), request.extensionProject(), true,
                operations.size(), operations, warnings);
    }

    private ParsedFqn parseTopLevelFqn(String fqn) {
        int dot = fqn.indexOf('.');
        if (dot <= 0 || dot == fqn.length() - 1) {
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_NAME,
                    "source FQN must be Kind.Name: " + fqn, false); //$NON-NLS-1$
        }
        return new ParsedFqn(MetadataKind.fromString(fqn.substring(0, dot)), fqn.substring(dot + 1));
    }

    private String effectiveExtensionName(String extensionProject, String sourceName) {
        if (extensionProject != null && extensionProject.contains(".") && !sourceName.startsWith("ар_")) { //$NON-NLS-1$ //$NON-NLS-2$
            return "ар_" + sourceName; //$NON-NLS-1$
        }
        return sourceName;
    }

    private List<String> coverageForKind(MetadataKind kind) {
        String token = kind.getFqnPrefix().toLowerCase(Locale.ROOT);
        List<String> coverage = new ArrayList<>();
        coverage.add("top_level_properties"); //$NON-NLS-1$
        coverage.add("children"); //$NON-NLS-1$
        coverage.add("TypeDescription_fields"); //$NON-NLS-1$
        if (!"role".equals(token)) { //$NON-NLS-1$
            coverage.add("modules"); //$NON-NLS-1$
        }
        if ("catalog".equals(token) || "informationregister".equals(token)) { //$NON-NLS-1$ //$NON-NLS-2$
            coverage.add("forms"); //$NON-NLS-1$
        }
        if ("role".equals(token)) { //$NON-NLS-1$
            coverage.add("rights"); //$NON-NLS-1$
        }
        return coverage;
    }

    private List<String> skippedForKind(MetadataKind kind) {
        List<String> skipped = new ArrayList<>();
        skipped.add("source_deletion"); //$NON-NLS-1$
        if (kind == MetadataKind.BOT) {
            skipped.add("adopt_fallback_if_EDT_ModelObjectAdopter_rejects_Bot"); //$NON-NLS-1$
        }
        return skipped;
    }

    private record ParsedFqn(MetadataKind kind, String name) {
    }
}
