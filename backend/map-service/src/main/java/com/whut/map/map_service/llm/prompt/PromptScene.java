package com.whut.map.map_service.llm.prompt;

public enum PromptScene {
    CHAT("system-chat.txt"),
    RISK_EXPLANATION("system-risk-explanation.txt");

    private final String resourceName;

    PromptScene(String resourceName) {
        this.resourceName = resourceName;
    }

    public String resourceName() {
        return resourceName;
    }
}
