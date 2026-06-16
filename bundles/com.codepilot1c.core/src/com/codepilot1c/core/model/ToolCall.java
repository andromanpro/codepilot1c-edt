/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.model;

import java.util.Objects;

/**
 * Represents a tool call requested by the LLM.
 */
public class ToolCall {

    private final String id;
    private final String name;
    private final String arguments;
    private final boolean argumentsRepaired;

    /**
     * Creates a new tool call.
     *
     * @param id unique identifier for this tool call
     * @param name the name of the tool to call
     * @param arguments JSON string of arguments
     */
    public ToolCall(String id, String name, String arguments) {
        this(id, name, arguments, false);
    }

    /**
     * Creates a new tool call.
     *
     * @param id unique identifier for this tool call
     * @param name the name of the tool to call
     * @param arguments JSON string of arguments
     * @param argumentsRepaired whether arguments were reconstructed from a
     *        truncated/malformed payload and may be incomplete
     */
    public ToolCall(String id, String name, String arguments, boolean argumentsRepaired) {
        this.id = Objects.requireNonNull(id, "id"); //$NON-NLS-1$
        this.name = Objects.requireNonNull(name, "name"); //$NON-NLS-1$
        this.arguments = arguments != null ? arguments : "{}"; //$NON-NLS-1$
        this.argumentsRepaired = argumentsRepaired;
    }

    /**
     * Returns the unique ID of this tool call.
     *
     * @return the tool call ID
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the name of the tool to call.
     *
     * @return the tool name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the arguments as a JSON string.
     *
     * @return the arguments JSON
     */
    public String getArguments() {
        return arguments;
    }

    /**
     * Returns whether the arguments were reconstructed from a truncated or
     * malformed payload (e.g. streaming cut mid-JSON) and may be incomplete.
     *
     * @return true if arguments were repaired
     */
    public boolean isArgumentsRepaired() {
        return argumentsRepaired;
    }

    @Override
    public String toString() {
        return "ToolCall[id=" + id + ", name=" + name + ", arguments=" + arguments + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
