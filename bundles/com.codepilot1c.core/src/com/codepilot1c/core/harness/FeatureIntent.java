package com.codepilot1c.core.harness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record FeatureIntent(
        String originalPrompt,
        Set<FeatureFrame> frames,
        double confidence,
        List<String> reasons) {

    public FeatureIntent {
        originalPrompt = originalPrompt == null ? "" : originalPrompt; //$NON-NLS-1$
        frames = frames == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(frames));
        confidence = Math.max(0.0d, Math.min(1.0d, confidence));
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
