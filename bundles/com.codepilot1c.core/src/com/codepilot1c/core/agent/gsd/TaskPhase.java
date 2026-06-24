/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.gsd;

/**
 * GSD phase lifecycle states for the plugin's internal agent.
 *
 * <p>Mirrors the canonical GSD flow adapted to a single chat task:
 * {@code DISCUSS → PLAN → EXECUTE → VERIFY → DONE}. The agent advances through
 * these only when GSD mode is enabled ({@code AgentConfig.isGsdMode()}); each
 * transition is gated (clarifications resolved, plan reviewed, tasks executed,
 * goal-backward verification passed).</p>
 */
public enum TaskPhase {

    /** Gather context and resolve ambiguities via adaptive questioning. */
    DISCUSS,

    /** Decompose the goal into atomic tasks with acceptance criteria. */
    PLAN,

    /** Execute the plan task by task. */
    EXECUTE,

    /** Goal-backward verification: prove the goal is met, not just tasks done. */
    VERIFY,

    /** Goal achieved and verified. */
    DONE
}
