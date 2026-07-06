package com.codepilot1c.core.edt.extension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class ExtensionMigrationPlannerTest {

    @Test
    public void dryRunPlansRepresentativeKindsWithEffectiveFqns() {
        ExtensionMigrationPlanResult result = new ExtensionMigrationPlanner().plan(
                new ExtensionMigrationPlanRequest(
                        "ДО", //$NON-NLS-1$
                        "ДО.Артель", //$NON-NLS-1$
                        List.of(
                                "InformationRegister.аи_Регистр", //$NON-NLS-1$
                                "Catalog.аи_Каталог", //$NON-NLS-1$
                                "HTTPService.аи_HTTP", //$NON-NLS-1$
                                "CommonCommand.аи_Команда", //$NON-NLS-1$
                                "ScheduledJob.аи_Задание", //$NON-NLS-1$
                                "Bot.аи_Бот", //$NON-NLS-1$
                                "Role.аи_Роль"), //$NON-NLS-1$
                        false));

        assertTrue(result.dryRun());
        assertTrue(result.operationCount() >= 7);
        assertTrue(result.operations().stream().anyMatch(op -> op.targetFqn().equals("Bot.ар_аи_Бот"))); //$NON-NLS-1$
        assertTrue(result.operations().stream().anyMatch(op -> op.covers().contains("TypeDescription_fields"))); //$NON-NLS-1$
        assertTrue(result.operations().stream().anyMatch(op -> op.skipped().contains("source_deletion"))); //$NON-NLS-1$
    }

    @Test
    public void applyModeIsGatedUntilDryRunIsReviewed() {
        try {
            new ExtensionMigrationPlanner().plan(new ExtensionMigrationPlanRequest(
                    "ДО", "ДО.Артель", List.of("Catalog.Товары"), true)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (MetadataOperationException e) {
            assertEquals("KNOWLEDGE_REQUIRED", e.getCode().name()); //$NON-NLS-1$
            assertTrue(e.getMessage().contains("dry_run")); //$NON-NLS-1$
            return;
        }
        throw new AssertionError("Expected apply gating exception"); //$NON-NLS-1$
    }
}
