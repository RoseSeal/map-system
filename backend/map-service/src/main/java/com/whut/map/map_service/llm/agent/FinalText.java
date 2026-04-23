package com.whut.map.map_service.llm.agent;

public record FinalText(String text) implements AgentStepResult {

    public FinalText {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
