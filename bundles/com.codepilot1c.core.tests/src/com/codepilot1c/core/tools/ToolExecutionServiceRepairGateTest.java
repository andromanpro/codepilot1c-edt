package com.codepilot1c.core.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.model.ToolCall;

/**
 * Mutating tools must reject tool calls whose arguments were repaired from a
 * truncated payload — executing them risks writing incomplete content.
 */
public class ToolExecutionServiceRepairGateTest {

    private static final class FakeTool implements ITool {

        private final boolean mutating;

        private FakeTool(boolean mutating) {
            this.mutating = mutating;
        }

        @Override
        public String getName() {
            return "fake_tool"; //$NON-NLS-1$
        }

        @Override
        public String getDescription() {
            return "fake"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(
                    ToolResult.success("ok", ToolResult.ToolResultType.TEXT)); //$NON-NLS-1$
        }

        @Override
        public boolean isMutating() {
            return mutating;
        }
    }

    @Test
    public void mutatingToolWithRepairedArgumentsIsRejected() {
        ToolCall call = new ToolCall("call_1", "edit_file", "{\"path\":\"a.bsl\",\"content\":\"\"}", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        Optional<ToolResult> rejection =
                ToolExecutionService.rejectRepairedMutatingCall(new FakeTool(true), call);

        assertTrue(rejection.isPresent());
        assertFalse(rejection.get().isSuccess());
    }

    @Test
    public void mutatingToolWithCleanArgumentsIsAllowed() {
        ToolCall call = new ToolCall("call_1", "edit_file", "{\"path\":\"a.bsl\"}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(ToolExecutionService.rejectRepairedMutatingCall(new FakeTool(true), call).isEmpty());
    }

    @Test
    public void readOnlyToolWithRepairedArgumentsIsAllowed() {
        ToolCall call = new ToolCall("call_1", "read_file", "{\"path\":\"a.bsl\"}", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(ToolExecutionService.rejectRepairedMutatingCall(new FakeTool(false), call).isEmpty());
    }

    @Test
    public void unknownToolIsDeferredToUnknownToolHandling() {
        ToolCall call = new ToolCall("call_1", "missing_tool", "{}", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertTrue(ToolExecutionService.rejectRepairedMutatingCall(null, call).isEmpty());
    }
}
