package com.codepilot1c.core.harness;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FeaturePatternCatalog {

    private final Map<FeatureFrame, List<PatternId>> patternsByFrame = new EnumMap<>(FeatureFrame.class);

    public FeaturePatternCatalog() {
        patternsByFrame.put(FeatureFrame.REFERENCE_DATA, List.of(PatternId.CATALOG));
        patternsByFrame.put(FeatureFrame.BUSINESS_EVENT, List.of(PatternId.DOCUMENT_EVENT));
        patternsByFrame.put(FeatureFrame.RESOURCE_ACCOUNTING,
                List.of(PatternId.ACCUMULATION_BALANCE, PatternId.POSTING_MOVEMENT, PatternId.AVAILABILITY_CHECK));
        patternsByFrame.put(FeatureFrame.STATE_HISTORY, List.of(PatternId.INFORMATION_REGISTER));
        patternsByFrame.put(FeatureFrame.REPORTING, List.of(PatternId.REPORT_ON_DATE, PatternId.DCS_REPORT));
        patternsByFrame.put(FeatureFrame.FORM_UX, List.of(PatternId.MANAGED_FORM));
        patternsByFrame.put(FeatureFrame.MODULE_LOGIC, List.of(PatternId.POSTING_MOVEMENT));
    }

    public List<PatternId> defaultPatternsFor(FeatureFrame frame) {
        return patternsByFrame.getOrDefault(frame, List.of());
    }

    public List<PatternId> patternsFor(Set<FeatureFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        List<PatternId> result = new ArrayList<>();
        for (FeatureFrame frame : FeatureFrame.values()) {
            if (!frames.contains(frame)) {
                continue;
            }
            for (PatternId pattern : defaultPatternsFor(frame)) {
                if (!result.contains(pattern)) {
                    result.add(pattern);
                }
            }
        }
        return List.copyOf(result);
    }
}
