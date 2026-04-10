package com.whut.map.map_service.llm.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateServiceTest {

    @Test
    void loadsChatAndRiskExplanationTemplates() {
        PromptTemplateService service = new PromptTemplateService();

        assertThat(service.getSystemPrompt(PromptScene.CHAT))
                .contains("航行安全助手")
                .doesNotContain("\r");
        assertThat(service.getSystemPrompt(PromptScene.RISK_EXPLANATION))
                .contains("风险描述模块")
                .contains("相对方位")
                .doesNotContain("\r");
    }

    @Test
    void missingTemplateFailsFast() {
        assertThatThrownBy(() -> new PromptTemplateService("prompts-missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing prompt template resource");
    }

    @Test
    void blankTemplateFailsFast() {
        assertThatThrownBy(() -> new PromptTemplateService("test-prompts/blank"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Prompt template resource is blank");
    }
}
