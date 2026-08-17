package com.codepilot1c.core.tools;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.google.gson.Gson;

import sun.misc.Unsafe;

abstract class DelegationToolTestSupport {

    private LlmProviderRegistry previousRegistry;

    @Before
    public void installConfiguredProvider() throws Exception {
        previousRegistry = installRegistry(registryWithLegacyProvider(new FakeProvider()));
    }

    @After
    public void restoreProviderRegistry() throws Exception {
        if (previousRegistry != null) {
            installRegistry(previousRegistry);
            previousRegistry = null;
        }
    }

    protected ToolExecutionContext context(String profileId, int depth) {
        AgentProfile profile = AgentProfileRegistry.getInstance().getProfile(profileId)
                .orElseThrow(() -> new AssertionError("Missing profile: " + profileId)); //$NON-NLS-1$
        return ToolExecutionContext.of(profile, depth);
    }

    protected static ToolRegistry placeholderRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setToolRegistryField(registry, "tools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setToolRegistryField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setToolRegistryField(registry, "gson", new Gson()); //$NON-NLS-1$
        return registry;
    }

    private static LlmProviderRegistry registryWithLegacyProvider(ILlmProvider provider) throws Exception {
        Constructor<LlmProviderRegistry> constructor = LlmProviderRegistry.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        LlmProviderRegistry registry = constructor.newInstance();

        Field providersField = LlmProviderRegistry.class.getDeclaredField("legacyProviders"); //$NON-NLS-1$
        providersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ILlmProvider> providers =
                (Map<String, ILlmProvider>) providersField.get(registry);
        providers.put(provider.getId(), provider);

        Field initializedField = LlmProviderRegistry.class.getDeclaredField("initialized"); //$NON-NLS-1$
        initializedField.setAccessible(true);
        initializedField.set(registry, true);
        return registry;
    }

    private static LlmProviderRegistry installRegistry(LlmProviderRegistry registry) throws Exception {
        Field instanceField = LlmProviderRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        instanceField.setAccessible(true);
        LlmProviderRegistry previous = (LlmProviderRegistry) instanceField.get(null);
        instanceField.set(null, registry);
        return previous;
    }

    private static void setToolRegistryField(ToolRegistry registry, String name, Object value)
            throws Exception {
        Field field = ToolRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(registry, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    protected static final class CapturingExecutor implements TaskTool.SubagentExecutor {
        AgentConfig config;
        String prompt;
        AgentProfile profile;

        @Override
        public AgentResult run(
                ILlmProvider provider,
                ToolRegistry toolRegistry,
                AgentProfile profile,
                String prompt,
                AgentConfig config) {
            this.profile = profile;
            this.prompt = prompt;
            this.config = config;
            return AgentResult.success("ok", Collections.emptyList(), 1, 1, 5); //$NON-NLS-1$
        }
    }

    private static final class FakeProvider implements ILlmProvider {
        @Override
        public String getId() {
            return "fake"; //$NON-NLS-1$
        }

        @Override
        public String getDisplayName() {
            return "Fake"; //$NON-NLS-1$
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public CompletableFuture<LlmResponse> complete(LlmRequest request) {
            return CompletableFuture.completedFuture(LlmResponse.of("ok")); //$NON-NLS-1$
        }

        @Override
        public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
            consumer.accept(LlmStreamChunk.complete(LlmResponse.FINISH_REASON_STOP));
        }

        @Override
        public void cancel() {
        }

        @Override
        public void dispose() {
        }
    }
}
