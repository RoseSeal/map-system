package com.whut.map.map_service.llm.agent;

public sealed interface AgentLoopResult
        permits AgentLoopResult.Completed,
                AgentLoopResult.MaxIterationsExceeded,
                AgentLoopResult.ProviderFailed,
                AgentLoopResult.ToolFailed {

    record Completed(
            String finalText,
            int iterations,
            int toolCallCount,
            String finalizingStepId,
            String provider
    ) implements AgentLoopResult {}

    record MaxIterationsExceeded(
            int iterations,
            int toolCallCount
    ) implements AgentLoopResult {}

    record ProviderFailed(
            String errorCode,
            String message,
            Throwable cause
    ) implements AgentLoopResult {}

    record ToolFailed(
            String callId,
            String toolName,
            String message,
            Throwable cause
    ) implements AgentLoopResult {}

    static Completed completed(String finalText, int iterations, int toolCallCount) {
        return new Completed(finalText, iterations, toolCallCount, null, null);
    }

    static Completed completed(String finalText, int iterations, int toolCallCount, String finalizingStepId) {
        return new Completed(finalText, iterations, toolCallCount, finalizingStepId, null);
    }

    static Completed completed(
            String finalText,
            int iterations,
            int toolCallCount,
            String finalizingStepId,
            String provider
    ) {
        return new Completed(finalText, iterations, toolCallCount, finalizingStepId, provider);
    }

    static MaxIterationsExceeded maxIterationsExceeded(int iterations, int toolCallCount) {
        return new MaxIterationsExceeded(iterations, toolCallCount);
    }

    static ProviderFailed providerFailed(String errorCode, String message, Throwable cause) {
        return new ProviderFailed(errorCode, message, cause);
    }

    static ToolFailed toolFailed(String callId, String toolName, String message, Throwable cause) {
        return new ToolFailed(callId, toolName, message, cause);
    }
}
