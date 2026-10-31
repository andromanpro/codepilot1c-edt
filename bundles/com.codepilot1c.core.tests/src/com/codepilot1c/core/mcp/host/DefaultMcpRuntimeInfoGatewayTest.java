package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Readiness contract of the runtime-info boundary that backs {@code GET /health/ready} and the
 * MCP {@code initialize} metadata.
 *
 * <p>The staged semantics themselves live in {@link McpSemanticReadinessProbeTest}; this class
 * pins that the boundary really delegates to that probe rather than re-deriving readiness from a
 * single predicate.</p>
 */
public class DefaultMcpRuntimeInfoGatewayTest {

    @Test
    public void readinessSurfacesTheFailingStageInsteadOfAGenericMessage() {
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public void ensureWorkspaceRuntimeAvailable() {
                // the Eclipse workspace is open
            }

            @Override
            public void ensureEdtServicesAvailable() {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                        "IConfigurationProvider is unavailable in EDT runtime", false); //$NON-NLS-1$
            }
        };
        DefaultMcpRuntimeInfoGateway runtimeInfo = new DefaultMcpRuntimeInfoGateway(
                new McpSemanticReadinessProbe(gateway, () -> true, List::of));

        McpReadiness readiness = runtimeInfo.readiness();

        assertFalse(readiness.ready());
        assertEquals("starting", readiness.services()); //$NON-NLS-1$
        assertTrue(readiness.reason().contains("IConfigurationProvider")); //$NON-NLS-1$
    }

    @Test
    public void aWorkspaceWithoutEdtServicesIsNotReady() {
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public void ensureWorkspaceRuntimeAvailable() {
                // the Eclipse workspace is open, which alone must not publish readiness
            }

            @Override
            public void ensureEdtServicesAvailable() {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                        "IDtProjectManager is unavailable in EDT runtime", false); //$NON-NLS-1$
            }
        };
        DefaultMcpRuntimeInfoGateway runtimeInfo = new DefaultMcpRuntimeInfoGateway(
                new McpSemanticReadinessProbe(gateway, () -> true, List::of));

        assertFalse(runtimeInfo.readiness().ready());
    }

    @Test
    public void readinessIsAvailableWhenEverySemanticStagePasses() {
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public void ensureWorkspaceRuntimeAvailable() {
                // available
            }

            @Override
            public void ensureEdtServicesAvailable() {
                // available
            }

            @Override
            public void ensureMetadataReadCapability() {
                // available
            }
        };
        DefaultMcpRuntimeInfoGateway runtimeInfo = new DefaultMcpRuntimeInfoGateway(
                new McpSemanticReadinessProbe(gateway, () -> true, List::of));

        McpReadiness readiness = runtimeInfo.readiness();

        assertTrue(readiness.ready());
        assertEquals("ready", readiness.services()); //$NON-NLS-1$
    }
}
