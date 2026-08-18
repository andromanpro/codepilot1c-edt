/* SPDX-License-Identifier: AGPL-3.0-only */
package com.codepilot1c.cli.shell.broker;

import java.util.concurrent.CompletionStage;

/** Asynchronous capability probe for the connected EDT LLM broker. */
@FunctionalInterface
public interface BrokerProbe {
    CompletionStage<BrokerInfo> probe();
}
