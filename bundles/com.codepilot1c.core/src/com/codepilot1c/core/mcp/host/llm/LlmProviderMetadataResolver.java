package com.codepilot1c.core.mcp.host.llm;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.config.DynamicLlmProvider;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.settings.VibePreferenceConstants;

/** Resolves only the provider fields approved for the LLM broker wire API. */
@FunctionalInterface
public interface LlmProviderMetadataResolver {

    LlmProviderMetadata resolve(ILlmProvider provider);

    static LlmProviderMetadataResolver defaults() {
        return LlmProviderMetadataResolver::resolveDefault;
    }

    private static LlmProviderMetadata resolveDefault(ILlmProvider provider) {
        if (provider instanceof DynamicLlmProvider dynamicProvider) {
            LlmProviderConfig config = dynamicProvider.getConfig();
            return new LlmProviderMetadata(
                    provider.getId(),
                    provider.getDisplayName(),
                    config.getType() != null ? config.getType().getId() : "unknown", //$NON-NLS-1$
                    config.getModel(),
                    config.isStreamingEnabled());
        }

        String id = provider.getId();
        IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        if ("claude".equals(id)) { //$NON-NLS-1$
            return metadata(provider, "anthropic", //$NON-NLS-1$
                    preferences.get(VibePreferenceConstants.PREF_CLAUDE_MODEL, "")); //$NON-NLS-1$
        }
        if ("openai".equals(id)) { //$NON-NLS-1$
            return metadata(provider, "openai", //$NON-NLS-1$
                    preferences.get(VibePreferenceConstants.PREF_OPENAI_MODEL, "")); //$NON-NLS-1$
        }
        if ("ollama".equals(id)) { //$NON-NLS-1$
            return metadata(provider, "ollama", //$NON-NLS-1$
                    preferences.get(VibePreferenceConstants.PREF_OLLAMA_MODEL, "")); //$NON-NLS-1$
        }
        return metadata(provider, "unknown", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static LlmProviderMetadata metadata(ILlmProvider provider, String type, String model) {
        return new LlmProviderMetadata(provider.getId(), provider.getDisplayName(), type, model,
                provider.supportsStreaming());
    }
}
