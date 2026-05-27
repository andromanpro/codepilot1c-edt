package com.codepilot1c.core.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FeatureBlueprintFactory {

    public static final String GATE_SEMANTIC_EDT_TOOLS = "semantic_edt_tools"; //$NON-NLS-1$
    public static final String GATE_VALIDATION_TOKEN_CHAIN = "validation_token_chain"; //$NON-NLS-1$
    public static final String GATE_DIAGNOSTICS_AFTER_MUTATION = "diagnostics_after_mutation"; //$NON-NLS-1$
    public static final String GATE_NO_STRUCTURED_ARTIFACT_FILE_EDIT = "no_structured_artifact_file_edit"; //$NON-NLS-1$
    public static final String GATE_REPORT_PERIOD_BOUNDARY_EXPLICIT = "report_period_boundary_explicit"; //$NON-NLS-1$
    public static final String GATE_AGGREGATE_CONSUMPTION_BY_KEY = "aggregate_consumption_by_key"; //$NON-NLS-1$

    private final FeaturePatternCatalog patternCatalog;

    public FeatureBlueprintFactory() {
        this(new FeaturePatternCatalog());
    }

    public FeatureBlueprintFactory(FeaturePatternCatalog patternCatalog) {
        this.patternCatalog = Objects.requireNonNull(patternCatalog, "patternCatalog"); //$NON-NLS-1$
    }

    public FeatureBlueprint create(FeatureIntent intent) {
        Objects.requireNonNull(intent, "intent"); //$NON-NLS-1$
        List<String> gates = new ArrayList<>();
        gates.add(GATE_SEMANTIC_EDT_TOOLS);
        gates.add(GATE_VALIDATION_TOKEN_CHAIN);
        gates.add(GATE_DIAGNOSTICS_AFTER_MUTATION);
        gates.add(GATE_NO_STRUCTURED_ARTIFACT_FILE_EDIT);
        if (intent.frames().contains(FeatureFrame.REPORTING)) {
            gates.add(GATE_REPORT_PERIOD_BOUNDARY_EXPLICIT);
        }
        if (intent.frames().contains(FeatureFrame.RESOURCE_ACCOUNTING)) {
            gates.add(GATE_AGGREGATE_CONSUMPTION_BY_KEY);
        }
        return new FeatureBlueprint(intent, patternCatalog.patternsFor(intent.frames()), gates);
    }
}
