/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.prompts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

import com.codepilot1c.core.memory.prompt.PromptAssemblyContext;
import com.codepilot1c.core.memory.prompt.PromptContextContributorRegistry;
import com.codepilot1c.core.provider.ProviderSelectionGate;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionManager;
import com.codepilot1c.core.settings.PromptTemplateService;
import com.codepilot1c.core.skills.SkillCatalog;
import com.codepilot1c.core.skills.SkillDefinition;

/**
 * Single prompt assembly path for runtime, UI, and MCP host.
 */
public final class SystemPromptAssembler {

    private static final String PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    public record PromptAssembly(
            String prompt,
            List<InstructionContextService.InstructionLayer> agentsLayers,
            List<InstructionContextService.InstructionLayer> codeLayers,
            List<SkillDefinition> skills,
            boolean backendSelectedInUi) {
    }

    /** Extended assembly input with project and session context for memory contributors. */
    public record AssemblyInput(
            String basePrompt,
            String promptAddition,
            String profileName,
            List<String> requestedSkills,
            String projectPath,
            String sessionId,
            String currentUserQuery) {

        public AssemblyInput(String basePrompt, String promptAddition,
                String profileName, List<String> requestedSkills,
                String projectPath, String sessionId) {
            this(basePrompt, promptAddition, profileName, requestedSkills, projectPath, sessionId, null);
        }

        public static AssemblyInput of(String basePrompt, String promptAddition,
                String profileName, List<String> requestedSkills) {
            return new AssemblyInput(basePrompt, promptAddition, profileName,
                    requestedSkills, null, null, null);
        }

        public static AssemblyInput of(String basePrompt, String promptAddition,
                String profileName, List<String> requestedSkills, String currentUserQuery) {
            return new AssemblyInput(basePrompt, promptAddition, profileName,
                    requestedSkills, null, null, currentUserQuery);
        }
    }

    private static final SystemPromptAssembler INSTANCE =
            new SystemPromptAssembler(new InstructionContextService(), new SkillCatalog());

    private final InstructionContextService instructionContextService;
    private final SkillCatalog skillCatalog;
    private final ContributorAssembler contributorAssembler;

    @FunctionalInterface
    interface ContributorAssembler {
        String assemble(PromptAssemblyContext ctx);
    }

    public static SystemPromptAssembler getInstance() {
        return INSTANCE;
    }

    public SystemPromptAssembler(InstructionContextService instructionContextService, SkillCatalog skillCatalog) {
        this(instructionContextService, skillCatalog, SystemPromptAssembler::assembleRegisteredContributors);
    }

    SystemPromptAssembler(InstructionContextService instructionContextService, SkillCatalog skillCatalog,
            ContributorAssembler contributorAssembler) {
        this.instructionContextService = Objects.requireNonNull(instructionContextService, "instructionContextService"); //$NON-NLS-1$
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog"); //$NON-NLS-1$
        this.contributorAssembler = Objects.requireNonNull(contributorAssembler, "contributorAssembler"); //$NON-NLS-1$
    }

    public String assemble(String basePrompt, String promptAddition, String profileName, List<String> requestedSkills) {
        return assembleDetailed(basePrompt, promptAddition, profileName, requestedSkills).prompt();
    }

    public String assemble(String basePrompt, String promptAddition, String profileName, Set<String> requestedSkills) {
        return assemble(basePrompt, promptAddition, profileName,
                requestedSkills != null ? List.copyOf(requestedSkills) : List.of());
    }

    public PromptAssembly assembleDetailed(String basePrompt, String promptAddition, String profileName,
            List<String> requestedSkills) {
        return assembleDetailed(basePrompt, promptAddition, profileName, requestedSkills,
                ProviderSelectionGate.isCodePilotSelectedInUi());
    }

    public PromptAssembly assembleDetailedForCurrentSession(String basePrompt, String promptAddition,
            String profileName, List<String> requestedSkills, String currentUserQuery) {
        return assembleDetailedForCurrentSession(basePrompt, promptAddition, profileName, requestedSkills,
                currentUserQuery, ProviderSelectionGate.isCodePilotSelectedInUi());
    }

    PromptAssembly assembleDetailedForCurrentSession(String basePrompt, String promptAddition,
            String profileName, List<String> requestedSkills, String currentUserQuery,
            boolean backendSelectedInUi) {
        String projectPath = null;
        String sessionId = null;
        try {
            Session current = SessionManager.getInstance().getOrCreateCurrentSession();
            if (current != null) {
                projectPath = current.getProjectPath();
                sessionId = current.getId();
            }
        } catch (RuntimeException | LinkageError e) {
            // SessionManager may not be initialized yet during startup.
        }
        return assembleDetailed(new AssemblyInput(basePrompt, promptAddition, profileName,
                requestedSkills, projectPath, sessionId, currentUserQuery), backendSelectedInUi);
    }

    PromptAssembly assembleDetailed(String basePrompt, String promptAddition, String profileName,
            List<String> requestedSkills, boolean backendSelectedInUi) {
        // Auto-resolve projectPath and sessionId from current session (BLOCKER #1 fix)
        // Use getOrCreateCurrentSession() which ensures project binding
        String projectPath = null;
        String sessionId = null;
        try {
            Session current = SessionManager.getInstance().getOrCreateCurrentSession();
            if (current != null) {
                projectPath = current.getProjectPath();
                sessionId = current.getId();
            }
        } catch (RuntimeException | LinkageError e) {
            // SessionManager may not be initialized yet during startup
        }
        return assembleDetailed(new AssemblyInput(basePrompt, promptAddition, profileName,
                requestedSkills, projectPath, sessionId, null), backendSelectedInUi);
    }

    /**
     * Full assembly with project/session context for memory contributors.
     *
     * @param input assembly input with project path and session id
     * @return assembled prompt with all layers including memory contributors
     */
    public PromptAssembly assembleDetailed(AssemblyInput input) {
        return assembleDetailed(input, ProviderSelectionGate.isCodePilotSelectedInUi());
    }

    PromptAssembly assembleDetailed(AssemblyInput input, boolean backendSelectedInUi) {
        List<InstructionContextService.InstructionLayer> agentsLayers =
                instructionContextService.loadAgentsLayers(input.projectPath());
        List<InstructionContextService.InstructionLayer> codeLayers =
                instructionContextService.loadCodeLayers(backendSelectedInUi, input.projectPath());
        List<SkillDefinition> skills = loadRequestedSkills(input.requestedSkills(), backendSelectedInUi);
        String effectiveBasePrompt = AgentPromptTemplates.adaptForBackend(input.basePrompt(), backendSelectedInUi);
        String effectivePromptAddition = AgentPromptTemplates.adaptForBackend(input.promptAddition(), backendSelectedInUi);

        StringBuilder prompt = new StringBuilder();
        appendDistinct(prompt, effectiveBasePrompt);
        appendDistinct(prompt, effectivePromptAddition);
        appendInstructionLayers(prompt, "AGENTS.md", agentsLayers); //$NON-NLS-1$
        appendInstructionLayers(prompt, "Code.md", codeLayers); //$NON-NLS-1$

        // Memory contributors: Platform Knowledge [300], Project Metadata [400], Memory [500]
        appendContributors(prompt, input, backendSelectedInUi);

        // Skill catalog: progressive disclosure — show available skills before loading any
        appendSkillCatalog(prompt, skills, backendSelectedInUi);

        appendSkills(prompt, skills);

        String effectivePrompt = PromptTemplateService.getInstance().applySystemPrompt(prompt.toString().strip());
        return new PromptAssembly(effectivePrompt, agentsLayers, codeLayers, skills, backendSelectedInUi);
    }

    /**
     * Appends memory contributor sections between Code.md and Skills.
     */
    private void appendContributors(StringBuilder prompt, AssemblyInput input, boolean backendSelectedInUi) {
        PromptAssemblyContext ctx = PromptAssemblyContext.of(
                input.projectPath(),
                input.profileName(),
                backendSelectedInUi,
                input.sessionId(),
                PromptAssemblyContext.DEFAULT_GLOBAL_BUDGET,
                input.currentUserQuery());

        String contributions = contributorAssembler.assemble(ctx);
        if (!contributions.isBlank()) {
            prompt.append(contributions);
        }
    }

    private static String assembleRegisteredContributors(PromptAssemblyContext ctx) {
        try {
            PromptContextContributorRegistry registry = PromptContextContributorRegistry.getInstance();
            if (registry.size() == 0) {
                return ""; //$NON-NLS-1$
            }
            return registry.assembleContributions(ctx);
        } catch (RuntimeException | LinkageError e) {
            logWarning("Prompt context contributor registry unavailable during prompt assembly", e); //$NON-NLS-1$
            return ""; //$NON-NLS-1$
        }
    }

    private static void logWarning(String message, Throwable error) {
        try {
            Bundle bundle = Platform.getBundle(PLUGIN_ID);
            if (bundle != null) {
                Platform.getLog(bundle).log(new Status(IStatus.WARNING, PLUGIN_ID, message, error));
            }
        } catch (RuntimeException ignored) {
            // Plain JUnit execution can run without an initialized OSGi bundle.
        }
    }

    private List<SkillDefinition> loadRequestedSkills(List<String> requestedSkills, boolean backendSelectedInUi) {
        if (requestedSkills == null || requestedSkills.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String requestedSkill : requestedSkills) {
            if (requestedSkill != null && !requestedSkill.isBlank()) {
                distinct.add(requestedSkill.trim());
            }
        }

        List<SkillDefinition> resolved = new ArrayList<>();
        for (String skillName : distinct) {
            skillCatalog.getSkill(skillName, backendSelectedInUi).ifPresent(resolved::add);
        }
        return List.copyOf(resolved);
    }

    private void appendDistinct(StringBuilder prompt, String section) {
        String normalized = section != null ? section.strip() : ""; //$NON-NLS-1$
        if (normalized.isEmpty()) {
            return;
        }
        if (prompt.indexOf(normalized) >= 0) {
            return;
        }
        if (!prompt.isEmpty()) {
            prompt.append("\n\n"); //$NON-NLS-1$
        }
        prompt.append(normalized);
    }

    private void appendInstructionLayers(StringBuilder prompt, String fileLabel,
            List<InstructionContextService.InstructionLayer> layers) {
        if (layers == null || layers.isEmpty()) {
            return;
        }
        prompt.append("\n\n## Layered Context: ").append(fileLabel).append("\n"); //$NON-NLS-1$
        for (InstructionContextService.InstructionLayer layer : layers) {
            prompt.append("\n### Source: ").append(layer.sourcePath()).append("\n\n"); //$NON-NLS-1$
            if (layer.status() != null && !layer.status().isBlank()) {
                prompt.append("Status: ").append(layer.status()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (layer.sizeBytes() > 0 || layer.readBytes() > 0) {
                prompt.append("Bytes: ").append(layer.readBytes()).append("/")
                        .append(layer.sizeBytes()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (layer.warning() != null && !layer.warning().isBlank()) {
                prompt.append("Warning: ").append(layer.warning()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if ((layer.status() != null && !layer.status().isBlank())
                    || layer.sizeBytes() > 0 || layer.readBytes() > 0
                    || (layer.warning() != null && !layer.warning().isBlank())) {
                prompt.append("\n"); //$NON-NLS-1$
            }
            prompt.append(layer.content()).append("\n"); //$NON-NLS-1$
        }
    }

    /**
     * Renders a skill catalog section showing all available skills with descriptions.
     * This enables the model to discover and auto-load skills based on task context.
     * Skills already loaded via requestedSkills are excluded from the catalog.
     */
    private void appendSkillCatalog(StringBuilder prompt, List<SkillDefinition> loadedSkills,
            boolean backendSelectedInUi) {
        List<SkillDefinition> visible = skillCatalog.discoverVisibleSkills(backendSelectedInUi);
        if (visible.isEmpty()) {
            return;
        }
        // Exclude already-loaded skills from the catalog
        java.util.Set<String> loadedNames = loadedSkills.stream()
                .map(s -> s.name().toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        List<SkillDefinition> catalogSkills = visible.stream()
                .filter(s -> !loadedNames.contains(s.name().toLowerCase(java.util.Locale.ROOT)))
                .toList();
        if (catalogSkills.isEmpty()) {
            return;
        }

        prompt.append("\n\n## Доступные навыки (Skills)\n\n"); //$NON-NLS-1$
        prompt.append("При получении задачи загрузи подходящий skill через инструмент skill(name=...) ДО начала работы.\n\n"); //$NON-NLS-1$
        for (SkillDefinition skill : catalogSkills) {
            prompt.append("- **").append(skill.name()).append("**"); //$NON-NLS-1$ //$NON-NLS-2$
            if (skill.description() != null && !skill.description().isBlank()) {
                prompt.append(": ").append(skill.description()); //$NON-NLS-1$
            }
            prompt.append("\n"); //$NON-NLS-1$
        }
    }

    private void appendSkills(StringBuilder prompt, List<SkillDefinition> skills) {
        if (skills == null || skills.isEmpty()) {
            return;
        }
        prompt.append("\n\n## Loaded Skills\n"); //$NON-NLS-1$
        for (SkillDefinition skill : skills) {
            prompt.append("\n### Skill: ").append(skill.name()).append("\n"); //$NON-NLS-1$
            prompt.append("Source: ").append(skill.sourcePath()).append("\n"); //$NON-NLS-1$
            if (!skill.allowedTools().isEmpty()) {
                prompt.append("Allowed tools: ").append(String.join(", ", skill.allowedTools())).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (skill.backendOnly()) {
                prompt.append("Backend-only: true\n"); //$NON-NLS-1$
            }
            prompt.append("\n").append(skill.body()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
