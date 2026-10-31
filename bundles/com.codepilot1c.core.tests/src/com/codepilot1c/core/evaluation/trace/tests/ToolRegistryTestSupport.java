package com.codepilot1c.core.evaluation.trace.tests;

import com.codepilot1c.core.tools.ToolRegistry;

final class ToolRegistryTestSupport {

    private ToolRegistryTestSupport() {
    }

    static ToolRegistry createIsolatedRegistry() {
        return ToolRegistry.createDetached();
    }

    static ToolRegistry.ScopedTestLease installScoped(ToolRegistry registry) {
        return ToolRegistry.installScopedForTesting(registry);
    }
}
