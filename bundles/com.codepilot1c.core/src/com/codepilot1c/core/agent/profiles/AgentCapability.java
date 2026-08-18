/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

/**
 * Coarse capability level used to constrain profile delegation.
 */
public enum AgentCapability {
    READ_ONLY,
    MUTATING;

    /**
     * Returns whether this capability includes the requested capability.
     *
     * @param other requested capability
     * @return {@code true} when the request is covered
     */
    public boolean covers(AgentCapability other) {
        return ordinal() >= other.ordinal();
    }

    /**
     * Returns the stronger of two capabilities.
     *
     * @param first first capability
     * @param second second capability
     * @return stronger capability
     */
    public static AgentCapability max(AgentCapability first, AgentCapability second) {
        return first.covers(second) ? first : second;
    }
}
