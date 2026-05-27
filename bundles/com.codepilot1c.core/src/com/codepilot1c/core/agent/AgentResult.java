/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.codepilot1c.core.model.LlmMessage;

/**
 * Результат выполнения агента.
 *
 * <p>Содержит финальное состояние, ответ LLM, историю сообщений
 * и статистику выполнения.</p>
 */
public class AgentResult {

    private final AgentState finalState;
    private final String finalResponse;
    private final List<LlmMessage> conversationHistory;
    private final int stepsExecuted;
    private final int toolCallsExecuted;
    private final long executionTimeMs;
    private final String errorMessage;
    private final Throwable error;

    private AgentResult(Builder builder) {
        this.finalState = Objects.requireNonNull(builder.finalState);
        this.finalResponse = builder.finalResponse;
        this.conversationHistory = builder.conversationHistory != null
                ? Collections.unmodifiableList(builder.conversationHistory)
                : Collections.emptyList();
        this.stepsExecuted = builder.stepsExecuted;
        this.toolCallsExecuted = builder.toolCallsExecuted;
        this.executionTimeMs = builder.executionTimeMs;
        this.errorMessage = builder.errorMessage;
        this.error = builder.error;
    }

    /**
     * Создает Builder для AgentResult.
     *
     * @return новый Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Создает успешный результат.
     *
     * @param response финальный ответ
     * @param history история сообщений
     * @param steps количество шагов
     * @param toolCalls количество вызовов инструментов
     * @param timeMs время выполнения
     * @return успешный результат
     */
    public static AgentResult success(String response, List<LlmMessage> history,
                                       int steps, int toolCalls, long timeMs) {
        return builder()
                .finalState(AgentState.COMPLETED)
                .finalResponse(response)
                .conversationHistory(history)
                .stepsExecuted(steps)
                .toolCallsExecuted(toolCalls)
                .executionTimeMs(timeMs)
                .build();
    }

    /**
     * Создает результат с ошибкой.
     *
     * @param error исключение
     * @param history история сообщений
     * @param steps количество шагов до ошибки
     * @param timeMs время выполнения
     * @return результат с ошибкой
     */
    public static AgentResult error(Throwable error, List<LlmMessage> history,
                                     int steps, long timeMs) {
        return builder()
                .finalState(AgentState.ERROR)
                .error(error)
                .errorMessage(error.getMessage())
                .conversationHistory(history)
                .stepsExecuted(steps)
                .executionTimeMs(timeMs)
                .build();
    }

    /**
     * Создает результат отмены.
     *
     * @param history история сообщений
     * @param steps количество шагов до отмены
     * @param timeMs время выполнения
     * @return результат отмены
     */
    public static AgentResult cancelled(List<LlmMessage> history, int steps, long timeMs) {
        return builder()
                .finalState(AgentState.CANCELLED)
                .errorMessage("Выполнение отменено пользователем")
                .conversationHistory(history)
                .stepsExecuted(steps)
                .executionTimeMs(timeMs)
                .build();
    }

    /**
     * Финальное состояние агента.
     *
     * @return состояние
     */
    public AgentState getFinalState() {
        return finalState;
    }

    /**
     * Финальный ответ LLM (если есть).
     *
     * @return ответ или null
     */
    public String getFinalResponse() {
        return finalResponse;
    }

    /**
     * Полная история диалога.
     *
     * @return неизменяемый список сообщений
     */
    public List<LlmMessage> getConversationHistory() {
        return conversationHistory;
    }

    /**
     * Количество выполненных шагов (итераций loop).
     *
     * @return количество шагов
     */
    public int getStepsExecuted() {
        return stepsExecuted;
    }

    /**
     * Количество выполненных вызовов инструментов.
     *
     * @return количество tool calls
     */
    public int getToolCallsExecuted() {
        return toolCallsExecuted;
    }

    /**
     * Время выполнения в миллисекундах.
     *
     * @return время в мс
     */
    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    /**
     * Сообщение об ошибке (если есть).
     *
     * @return сообщение или null
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Исключение (если есть).
     *
     * @return исключение или null
     */
    public Throwable getError() {
        return error;
    }

    /**
     * Проверяет, успешно ли завершился агент.
     *
     * @return true если успешно
     */
    public boolean isSuccess() {
        return finalState == AgentState.COMPLETED && errorMessage == null;
    }

    /**
     * Проверяет, был ли агент отменен.
     *
     * @return true если отменен
     */
    public boolean isCancelled() {
        return finalState == AgentState.CANCELLED;
    }

    /**
     * Проверяет, завершился ли агент с ошибкой.
     *
     * @return true если ошибка
     */
    public boolean isError() {
        return finalState == AgentState.ERROR;
    }

    @Override
    public String toString() {
        return "AgentResult{" +
                "state=" + finalState +
                ", steps=" + stepsExecuted +
                ", toolCalls=" + toolCallsExecuted +
                ", timeMs=" + executionTimeMs +
                (errorMessage != null ? ", error=" + errorMessage : "") +
                '}';
    }

    /**
     * Builder для AgentResult.
     */
    public static class Builder {
        private AgentState finalState;
        private String finalResponse;
        private List<LlmMessage> conversationHistory;
        private int stepsExecuted;
        private int toolCallsExecuted;
        private long executionTimeMs;
        private String errorMessage;
        private Throwable error;

        private Builder() {
        }

        public Builder finalState(AgentState state) {
            this.finalState = state;
            return this;
        }

        public Builder finalResponse(String response) {
            this.finalResponse = response;
            return this;
        }

        public Builder conversationHistory(List<LlmMessage> history) {
            this.conversationHistory = history;
            return this;
        }

        public Builder stepsExecuted(int steps) {
            this.stepsExecuted = steps;
            return this;
        }

        public Builder toolCallsExecuted(int toolCalls) {
            this.toolCallsExecuted = toolCalls;
            return this;
        }

        public Builder executionTimeMs(long timeMs) {
            this.executionTimeMs = timeMs;
            return this;
        }

        public Builder errorMessage(String message) {
            this.errorMessage = message;
            return this;
        }

        public Builder error(Throwable error) {
            this.error = error;
            return this;
        }

        public AgentResult build() {
            return new AgentResult(this);
        }
    }
}
