package com.whut.map.map_service.llm.agent.advisory;

import com.whut.map.map_service.llm.agent.AgentMessage;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.TextAgentMessage;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.llm.dto.LlmRiskWeatherContext;
import com.whut.map.map_service.llm.prompt.PromptScene;
import com.whut.map.map_service.llm.prompt.PromptTemplateService;
import com.whut.map.map_service.shared.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisoryPromptBuilderTest {

    private final PromptTemplateService promptTemplateService = new PromptTemplateService();
    private final AdvisoryPromptBuilder builder = new AdvisoryPromptBuilder(promptTemplateService);

    @Test
    void buildReturnsSystemAndUserMessages() {
        AgentSnapshot snapshot = new AgentSnapshot(7L, null, Map.of());

        List<AgentMessage> messages = builder.build(snapshot);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(TextAgentMessage.class);
        assertThat(messages.get(1)).isInstanceOf(TextAgentMessage.class);
        assertThat(((TextAgentMessage) messages.get(0)).role()).isEqualTo(ChatRole.SYSTEM);
        assertThat(((TextAgentMessage) messages.get(1)).role()).isEqualTo(ChatRole.USER);
    }

    @Test
    void systemPromptComesFromPromptTemplateService() {
        AgentSnapshot snapshot = new AgentSnapshot(1L, null, Map.of());

        List<AgentMessage> messages = builder.build(snapshot);

        assertThat(((TextAgentMessage) messages.get(0)).content())
                .isEqualTo(promptTemplateService.getSystemPrompt(PromptScene.ADVISORY));
    }

    @Test
    void userPromptContainsSnapshotAndRiskStats() {
        LlmRiskTargetContext alarm = LlmRiskTargetContext.builder().targetId("t-alarm").riskLevel(RiskLevel.ALARM).build();
        LlmRiskTargetContext safe = LlmRiskTargetContext.builder().targetId("t-safe").riskLevel(RiskLevel.SAFE).build();
        AgentSnapshot snapshot = new AgentSnapshot(
                42L,
                LlmRiskContext.builder().targets(List.of(alarm, safe)).build(),
                Map.of()
        );

        List<AgentMessage> messages = builder.build(snapshot);
        String userContent = ((TextAgentMessage) messages.get(1)).content();

        assertThat(userContent)
                .contains("当前快照版本：42")
                .contains("场景最高风险等级：ALARM")
                .contains("非 SAFE 目标数量：1");
    }

    @Test
    void userPromptAppendsWeatherSegmentForStorm() {
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .weatherCode("STORM")
                .seaState(8)
                .activeAlerts(List.of())
                .build();
        AgentSnapshot snapshot = new AgentSnapshot(
                10L,
                LlmRiskContext.builder().weather(weather).build(),
                Map.of()
        );

        String userContent = ((TextAgentMessage) builder.build(snapshot).get(1)).content();

        assertThat(userContent).contains("当前气象");
        assertThat(userContent).contains("STORM");
    }

    @Test
    void userPromptOmitsWeatherSegmentForClear() {
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .weatherCode("CLEAR")
                .activeAlerts(List.of())
                .build();
        AgentSnapshot snapshot = new AgentSnapshot(
                11L,
                LlmRiskContext.builder().weather(weather).build(),
                Map.of()
        );

        String userContent = ((TextAgentMessage) builder.build(snapshot).get(1)).content();

        assertThat(userContent).doesNotContain("当前气象");
    }

    @Test
    void userPromptOmitsWeatherSegmentWhenWeatherNull() {
        AgentSnapshot snapshot = new AgentSnapshot(
                12L,
                LlmRiskContext.builder().build(),
                Map.of()
        );

        String userContent = ((TextAgentMessage) builder.build(snapshot).get(1)).content();

        assertThat(userContent).doesNotContain("当前气象");
    }
}

