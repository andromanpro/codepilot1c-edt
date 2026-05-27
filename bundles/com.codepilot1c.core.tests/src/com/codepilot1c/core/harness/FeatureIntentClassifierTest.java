package com.codepilot1c.core.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.Test;

public class FeatureIntentClassifierTest {

    private final FeatureIntentClassifier classifier = new KeywordFeatureIntentClassifier();

    @Test
    public void classifiesGoodsAccountingAsCompositeFeature() {
        FeatureIntent intent = classifier.classify(
                "Нужно реализовать учет товаров: справочник Номенклатура, документы поступления и продажи, " //$NON-NLS-1$
                        + "остатки на складах, отчет по остаткам и логику проведения."); //$NON-NLS-1$

        assertEquals(
                Set.of(FeatureFrame.REFERENCE_DATA, FeatureFrame.BUSINESS_EVENT, FeatureFrame.RESOURCE_ACCOUNTING,
                        FeatureFrame.REPORTING, FeatureFrame.MODULE_LOGIC),
                intent.frames());
        assertTrue(intent.confidence() >= 0.8d);
        assertTrue(intent.reasons().size() >= 5);
    }

    @Test
    public void classifiesStatusHistoryAsStateHistory() {
        FeatureIntent intent = classifier.classify(
                "Нужно хранить историю статусов заказа с периодами действия."); //$NON-NLS-1$

        assertTrue(intent.frames().contains(FeatureFrame.STATE_HISTORY));
        assertTrue(intent.reasons().stream().anyMatch(reason -> reason.contains("STATE_HISTORY"))); //$NON-NLS-1$
    }

    @Test
    public void classifiesReportOnlyPromptAsReporting() {
        FeatureIntent intent = classifier.classify(
                "Build a report on current stock balances for an existing configuration."); //$NON-NLS-1$

        assertEquals(Set.of(FeatureFrame.REPORTING), intent.frames());
        assertTrue(intent.confidence() >= 0.45d);
    }

    @Test
    public void patternCatalogMapsFramesToDefaultPatterns() {
        FeaturePatternCatalog catalog = new FeaturePatternCatalog();

        assertEquals(List.of(PatternId.CATALOG), catalog.defaultPatternsFor(FeatureFrame.REFERENCE_DATA));
        assertEquals(List.of(PatternId.INFORMATION_REGISTER), catalog.defaultPatternsFor(FeatureFrame.STATE_HISTORY));
        assertEquals(List.of(PatternId.REPORT_ON_DATE, PatternId.DCS_REPORT),
                catalog.defaultPatternsFor(FeatureFrame.REPORTING));
        assertEquals(List.of(PatternId.ACCUMULATION_BALANCE, PatternId.POSTING_MOVEMENT,
                PatternId.AVAILABILITY_CHECK), catalog.defaultPatternsFor(FeatureFrame.RESOURCE_ACCOUNTING));
    }

    @Test
    public void blueprintAddsGenericAndFrameSpecificGates() {
        FeatureIntent intent = new FeatureIntent("goods accounting", //$NON-NLS-1$
                Set.of(FeatureFrame.RESOURCE_ACCOUNTING, FeatureFrame.REPORTING), 0.9d,
                List.of("matched RESOURCE_ACCOUNTING", "matched REPORTING")); //$NON-NLS-1$ //$NON-NLS-2$

        FeatureBlueprint blueprint = new FeatureBlueprintFactory(new FeaturePatternCatalog()).create(intent);

        assertTrue(blueprint.patterns().contains(PatternId.ACCUMULATION_BALANCE));
        assertTrue(blueprint.patterns().contains(PatternId.DCS_REPORT));
        assertEquals(List.of("semantic_edt_tools", "validation_token_chain", "diagnostics_after_mutation", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "no_structured_artifact_file_edit", "report_period_boundary_explicit", //$NON-NLS-1$ //$NON-NLS-2$
                "aggregate_consumption_by_key"), blueprint.verificationGates()); //$NON-NLS-1$
    }
}
