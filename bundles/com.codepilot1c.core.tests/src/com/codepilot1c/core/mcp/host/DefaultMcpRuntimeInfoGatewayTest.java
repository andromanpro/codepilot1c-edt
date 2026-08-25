package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

public class DefaultMcpRuntimeInfoGatewayTest {

    @Test
    public void readinessSurfacesTheMissingWorkspaceRuntimeReasonInsteadOfAGenericMessage() {
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public void ensureWorkspaceRuntimeAvailable() {
                throw new MetadataOperationException(
                        MetadataOperationCode.EDT_SERVICE_UNAVAILABLE,
                        "ResourcesPlugin workspace is unavailable in EDT runtime", false); //$NON-NLS-1$
            }
        };
        DefaultMcpRuntimeInfoGateway runtimeInfo = new DefaultMcpRuntimeInfoGateway(gateway);

        McpReadiness readiness = runtimeInfo.readiness();

        assertFalse(readiness.ready());
        assertEquals("ResourcesPlugin workspace is unavailable in EDT runtime", readiness.reason()); //$NON-NLS-1$
        assertEquals("starting", readiness.services()); //$NON-NLS-1$
    }

    @Test
    public void readinessStaysDegradedOnUnexpectedRuntimeFailures() {
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public void ensureWorkspaceRuntimeAvailable() {
                throw new IllegalStateException("boom"); //$NON-NLS-1$
            }
        };
        DefaultMcpRuntimeInfoGateway runtimeInfo = new DefaultMcpRuntimeInfoGateway(gateway);

        McpReadiness readiness = runtimeInfo.readiness();

        assertFalse(readiness.ready());
        assertEquals("degraded", readiness.services()); //$NON-NLS-1$
    }

    @Test
    public void readinessIsAvailableWhenMutationRuntimeIsReady() {
        EdtMetadataGateway gateway = new EdtMetadataGateway() {
            @Override
            public void ensureWorkspaceRuntimeAvailable() {
                // no-op: workspace/import runtime is available
            }
        };
        DefaultMcpRuntimeInfoGateway runtimeInfo = new DefaultMcpRuntimeInfoGateway(gateway);

        McpReadiness readiness = runtimeInfo.readiness();

        assertEquals(true, readiness.ready());
        assertEquals("ready", readiness.services()); //$NON-NLS-1$
    }
}
