package com.codepilot1c.core.harness;

import java.util.List;

public record FeatureBlueprint(
        FeatureIntent intent,
        List<PatternId> patterns,
        List<String> verificationGates) {

    public FeatureBlueprint {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        verificationGates = verificationGates == null ? List.of() : List.copyOf(verificationGates);
    }
}
