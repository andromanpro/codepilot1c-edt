/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.codepilot1c.core.permissions.PermissionRule;

/**
 * GSD development profile: full build access plus the {@code gsd_plan} tool, with
 * the GSD phase lifecycle (DISCUSS→PLAN→EXECUTE→VERIFY) enabled.
 *
 * <p>Selecting this profile turns on {@code AgentConfig.gsdMode}, which injects
 * the phase protocol into the system prompt and makes {@code gsd_plan} drive the
 * persistent plan artifact. Off by default — chosen explicitly (UI toggle/command
 * lands in a later phase), so trivial edits keep using the plain build flow.</p>
 */
public class GsdAgentProfile extends BuildAgentProfile {

    public static final String ID = "gsd"; //$NON-NLS-1$

    private final Set<String> allowedTools;

    public GsdAgentProfile() {
        Set<String> tools = new HashSet<>(super.getAllowedTools());
        tools.add("gsd_plan"); //$NON-NLS-1$
        this.allowedTools = tools;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "GSD"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Разработка по фазам GSD: DISCUSS→PLAN→EXECUTE→VERIFY с планом-артефактом, " //$NON-NLS-1$
                + "гейтами и goal-backward проверкой. Полный доступ + gsd_plan."; //$NON-NLS-1$
    }

    @Override
    public Set<String> getAllowedTools() {
        return allowedTools;
    }

    @Override
    public List<PermissionRule> getDefaultPermissions() {
        List<PermissionRule> permissions = new ArrayList<>(super.getDefaultPermissions());
        permissions.add(PermissionRule.allow("gsd_plan").forAllResources()); //$NON-NLS-1$
        return permissions;
    }

    @Override
    public boolean isGsdMode() {
        return true;
    }
}
