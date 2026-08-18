package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.Test;

public class McpContractMetadataServiceTest {

    @Test
    public void buildsStableExperimentalCodePilotMetadata() {
        McpContractMetadata metadata = new McpContractMetadata(
                1, "plugin-1.2.3", "2025.2", "gui", "/workspace",
                McpReadiness.available());
        McpContractMetadataService service = new McpContractMetadataService(() -> metadata);

        @SuppressWarnings("unchecked")
        Map<String, Object> codepilot = (Map<String, Object>) service.experimentalMetadata().get("codepilot"); //$NON-NLS-1$
        assertTrue(codepilot.get("contractVersion") instanceof Number); //$NON-NLS-1$
        assertEquals(1, ((Number) codepilot.get("contractVersion")).intValue()); //$NON-NLS-1$
        assertEquals("plugin-1.2.3", codepilot.get("pluginVersion")); //$NON-NLS-1$
        assertEquals("2025.2", codepilot.get("edtVersion")); //$NON-NLS-1$
        assertEquals("gui", codepilot.get("mode")); //$NON-NLS-1$
        assertEquals("/workspace", codepilot.get("workspace")); //$NON-NLS-1$

        @SuppressWarnings("unchecked")
        Map<String, Object> readiness = (Map<String, Object>) codepilot.get("readiness"); //$NON-NLS-1$
        assertEquals("ready", readiness.get("services")); //$NON-NLS-1$
        assertEquals(java.util.List.of(), readiness.get("projects")); //$NON-NLS-1$
        assertEquals("ready", readiness.get("status")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, readiness.get("ready")); //$NON-NLS-1$
        assertFalse(readiness.containsKey("reason")); //$NON-NLS-1$
    }

    @Test
    public void keepsDeterministicNotReadyShape() {
        McpReadiness readiness = McpReadiness.notReady("EDT runtime services are not ready"); //$NON-NLS-1$
        McpContractMetadata metadata = new McpContractMetadata(
                1, "plugin", "unknown", "gui", "unknown", readiness);
        McpContractMetadataService service = new McpContractMetadataService(() -> metadata);

        Map<String, Object> health = service.readiness().asHealthResponse();
        assertEquals("not_ready", health.get("status")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, health.get("ready")); //$NON-NLS-1$
        assertEquals("EDT runtime services are not ready", health.get("reason")); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> codepilot = (Map<String, Object>) service.experimentalMetadata().get("codepilot"); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> readinessMetadata = (Map<String, Object>) codepilot.get("readiness"); //$NON-NLS-1$
        assertEquals("degraded", readinessMetadata.get("services")); //$NON-NLS-1$
        assertEquals(java.util.List.of(), readinessMetadata.get("projects")); //$NON-NLS-1$
        assertTrue(health.keySet().containsAll(java.util.List.of("status", "ready", "reason"))); //$NON-NLS-1$
    }

    @Test
    public void readinessStatesAreLimitedToApprovedEnums() {
        assertEquals("starting", //$NON-NLS-1$
                McpReadiness.starting("EDT is starting").services()); //$NON-NLS-1$
        assertEquals("ready", McpReadiness.available().services()); //$NON-NLS-1$
        assertEquals(java.util.List.of(), McpReadiness.available().projects());

        ProjectReadiness project = new ProjectReadiness("Demo", "imported"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Map.of("name", "Demo", "state", "imported"), project.asMap()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        McpReadiness projectReadiness = new McpReadiness(true, "", "ready", //$NON-NLS-1$ //$NON-NLS-2$
                java.util.List.of(
                        new ProjectReadiness("Imported", "imported"), //$NON-NLS-1$ //$NON-NLS-2$
                        new ProjectReadiness("Building", "building"), //$NON-NLS-1$ //$NON-NLS-2$
                        new ProjectReadiness("Ready", "ready"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(java.util.List.of(
                Map.of("name", "Imported", "state", "imported"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Map.of("name", "Building", "state", "building"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Map.of("name", "Ready", "state", "ready")), //$NON-NLS-1$ //$NON-NLS-2$
                projectReadiness.asMetadata().get("projects")); //$NON-NLS-1$
    }

    @Test
    public void defaultProviderReadsValuesFromRuntimeBoundary() {
        DefaultMcpContractMetadataProvider provider = new DefaultMcpContractMetadataProvider(
                new McpRuntimeInfoGateway() {
                    @Override
                    public Optional<String> edtVersion() {
                        return Optional.of("2025.2"); //$NON-NLS-1$
                    }

                    @Override
                    public Optional<String> workspace() {
                        return Optional.of("/runtime-workspace"); //$NON-NLS-1$
                    }

                    @Override
                    public String mode() {
                        return "headless"; //$NON-NLS-1$
                    }

                    @Override
                    public McpReadiness readiness() {
                        return McpReadiness.available();
                    }
                },
                () -> "plugin-version"); //$NON-NLS-1$

        McpContractMetadata metadata = provider.snapshot();
        assertEquals(1, metadata.contractVersion()); //$NON-NLS-1$
        assertEquals("plugin-version", metadata.pluginVersion()); //$NON-NLS-1$
        assertEquals("2025.2", metadata.edtVersion()); //$NON-NLS-1$
        assertEquals("headless", metadata.mode()); //$NON-NLS-1$
        assertEquals("/runtime-workspace", metadata.workspace()); //$NON-NLS-1$
        assertTrue(metadata.readiness().ready());
    }

    @Test
    public void normalizesOnlyApprovedModeValues() {
        assertEquals("gui", new McpContractMetadata(1, "plugin", "unknown", "workbench", "unknown", //$NON-NLS-1$ //$NON-NLS-2$
                McpReadiness.available()).mode());
        assertEquals("headless", new McpContractMetadata(1, "plugin", "unknown", "headless", "unknown", //$NON-NLS-1$
                McpReadiness.available()).mode());
    }

    @Test
    public void defaultRuntimeModeUsesExplicitHeadlessSignal() {
        String previousHeadless = System.getProperty("codepilot1c.headless"); //$NON-NLS-1$
        String previousApplication = System.getProperty("eclipse.application"); //$NON-NLS-1$
        try {
            System.clearProperty("codepilot1c.headless"); //$NON-NLS-1$
            System.clearProperty("eclipse.application"); //$NON-NLS-1$
            assertEquals("gui", new DefaultMcpRuntimeInfoGateway().mode()); //$NON-NLS-1$
            System.setProperty("codepilot1c.headless", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("headless", new DefaultMcpRuntimeInfoGateway().mode()); //$NON-NLS-1$
            System.clearProperty("codepilot1c.headless"); //$NON-NLS-1$
            System.setProperty("eclipse.application", "com.codepilot1c.core.headless"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("headless", new DefaultMcpRuntimeInfoGateway().mode()); //$NON-NLS-1$
        } finally {
            if (previousHeadless == null) {
                System.clearProperty("codepilot1c.headless"); //$NON-NLS-1$
            } else {
                System.setProperty("codepilot1c.headless", previousHeadless); //$NON-NLS-1$
            }
            if (previousApplication == null) {
                System.clearProperty("eclipse.application"); //$NON-NLS-1$
            } else {
                System.setProperty("eclipse.application", previousApplication); //$NON-NLS-1$
            }
        }
    }
}
