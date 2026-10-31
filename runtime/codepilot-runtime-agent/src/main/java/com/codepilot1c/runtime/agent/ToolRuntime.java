/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.runtime.agent;

import java.util.List;
import java.util.concurrent.CompletionStage;

import com.google.gson.JsonObject;

/** Provider-neutral tool catalog and execution SPI. */
public interface ToolRuntime {
    List<ToolDefinition> tools();

    CompletionStage<ToolExecutionResult> execute(
            String name, JsonObject arguments, CancellationToken cancellation);
}
