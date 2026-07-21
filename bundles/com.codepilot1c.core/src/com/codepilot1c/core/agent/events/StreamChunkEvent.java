/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.events;

import com.codepilot1c.core.agent.AgentState;

/**
 * Событие получения chunk'а потокового ответа от LLM.
 */
public class StreamChunkEvent extends AgentEvent {

    private final String content;
    private final boolean isComplete;
    private final String finishReason;
    private final boolean reasoning;

    /**
     * Создает событие chunk'а.
     *
     * @param step текущий шаг
     * @param content содержимое chunk'а
     * @param isComplete завершен ли поток
     * @param finishReason причина завершения (если isComplete=true)
     */
    public StreamChunkEvent(int step, String content, boolean isComplete, String finishReason) {
        this(step, content, isComplete, finishReason, false);
    }

    /**
     * Создает событие chunk'а с признаком reasoning.
     *
     * @param step текущий шаг
     * @param content содержимое chunk'а
     * @param isComplete завершен ли поток
     * @param finishReason причина завершения (если isComplete=true)
     * @param reasoning true если это chunk «размышлений» (reasoning_content), а не основного ответа
     */
    public StreamChunkEvent(int step, String content, boolean isComplete, String finishReason, boolean reasoning) {
        super(AgentState.RUNNING, step);
        this.content = content;
        this.isComplete = isComplete;
        this.finishReason = finishReason;
        this.reasoning = reasoning;
    }

    /**
     * Создает событие с частичным контентом (основной ответ).
     *
     * @param step текущий шаг
     * @param content содержимое
     * @return событие
     */
    public static StreamChunkEvent partial(int step, String content) {
        return new StreamChunkEvent(step, content, false, null, false);
    }

    /**
     * Создает событие с частичным reasoning-контентом («размышления»).
     *
     * @param step текущий шаг
     * @param content содержимое reasoning
     * @return событие
     */
    public static StreamChunkEvent partialReasoning(int step, String content) {
        return new StreamChunkEvent(step, content, false, null, true);
    }

    /**
     * Создает событие завершения потока.
     *
     * @param step текущий шаг
     * @param finishReason причина завершения
     * @return событие
     */
    public static StreamChunkEvent complete(int step, String finishReason) {
        return new StreamChunkEvent(step, "", true, finishReason);
    }

    /**
     * Содержимое chunk'а.
     *
     * @return текст (может быть пустым)
     */
    public String getContent() {
        return content;
    }

    /**
     * Завершен ли поток.
     *
     * @return true если завершен
     */
    public boolean isComplete() {
        return isComplete;
    }

    /**
     * Причина завершения потока.
     *
     * @return причина или null
     */
    public String getFinishReason() {
        return finishReason;
    }

    /**
     * Является ли chunk «размышлением» (reasoning_content) в отличие от основного ответа.
     *
     * @return true если это reasoning-chunk
     */
    public boolean isReasoning() {
        return reasoning;
    }

    /**
     * Проверяет, содержит ли событие tool_calls.
     *
     * @return true если finish_reason == "tool_use" или "tool_calls"
     */
    public boolean hasToolCalls() {
        return "tool_use".equals(finishReason) || "tool_calls".equals(finishReason);
    }

    @Override
    public EventType getType() {
        return EventType.STREAM_CHUNK;
    }

    @Override
    public String toString() {
        if (isComplete) {
            return "StreamChunkEvent{complete, reason='" + finishReason + "'}";
        }
        return "StreamChunkEvent{content='" +
                content.substring(0, Math.min(30, content.length())) + "...'}";
    }
}
