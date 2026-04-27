package com.whut.map.map_service.llm.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateServiceTest {

    @Test
    void loadsAllPromptTemplates() {
        PromptTemplateService service = new PromptTemplateService();

        assertThat(service.getSystemPrompt(PromptScene.CHAT))
                .contains("航行安全助手")
                .doesNotContain("\r");
        assertThat(service.getSystemPrompt(PromptScene.RISK_EXPLANATION))
                .contains("风险描述模块")
                .contains("相对方位")
                .doesNotContain("\r");
        assertThat(service.getSystemPrompt(PromptScene.AGENT_CHAT))
                .contains("航海态势助理 AI")
                .contains("输出为自然语言")
                .doesNotContain("\r");
        assertThat(service.getSystemPrompt(PromptScene.ADVISORY))
                .contains("场景级航行建议")
                .contains("evidence_items")
                .doesNotContain("\r");
        assertThat(service.getSystemPrompt(PromptScene.RISK_EXPLANATION_USER_CONTEXT))
                .contains("【本船】")
                .contains("请输出风险描述")
                .doesNotContain("\r");
        assertThat(service.getSystemPrompt(PromptScene.ADVISORY_USER_CONTEXT))
                .contains("当前快照版本")
                .contains("输出有效 JSON")
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
