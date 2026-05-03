package com.whut.map.map_service.llm.agent.advisory;

import com.whut.map.map_service.llm.agent.AgentMessage;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.TextAgentMessage;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.llm.dto.LlmRiskWeatherContext;
import com.whut.map.map_service.llm.prompt.PromptScene;
import com.whut.map.map_service.llm.prompt.PromptTemplateService;
import com.whut.map.map_service.shared.domain.RiskLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class AdvisoryPromptBuilder {

    private final PromptTemplateService promptTemplateService;

    public List<AgentMessage> build(AgentSnapshot snapshot) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new TextAgentMessage(ChatRole.SYSTEM, promptTemplateService.getSystemPrompt(PromptScene.ADVISORY)));
        messages.add(new TextAgentMessage(ChatRole.USER, buildUserPrompt(snapshot)));
        return messages;
    }

    private String buildUserPrompt(AgentSnapshot snapshot) {
        RiskLevel highest = highestRiskLevel(snapshot);
        long nonSafeCount = nonSafeCount(snapshot);

        String base = promptTemplateService.getSystemPrompt(PromptScene.ADVISORY_USER_CONTEXT).formatted(
                snapshot.snapshotVersion(),
                highest != null ? highest.name() : "SAFE",
                nonSafeCount
        );

        String weatherSegment = buildWeatherSegment(snapshot);
        if (weatherSegment == null) {
            return base;
        }
        return base + "\n" + weatherSegment;
    }

    private String buildWeatherSegment(AgentSnapshot snapshot) {
        if (snapshot.riskContext() == null) return null;
        LlmRiskWeatherContext weather = snapshot.riskContext().getWeather();
        if (weather == null) return null;
        String code = weather.getWeatherCode();
        if ("CLEAR".equals(code)) {
            List<String> alerts = weather.getActiveAlerts();
            if (alerts == null || alerts.isEmpty()) return null;
        }

        StringBuilder sb = new StringBuilder("当前气象：");
        if (code != null) sb.append("[").append(code).append("]，");
        if (weather.getVisibilityNm() != null) sb.append("能见度 ").append(String.format("%.1f", weather.getVisibilityNm())).append(" nm，");
        if (weather.getWindSpeedKn() != null) sb.append("风 ").append(String.format("%.0f", weather.getWindSpeedKn())).append(" 节，");
        if (weather.getSurfaceCurrentSpeedKn() != null) {
            sb.append("水流 ").append(String.format("%.1f", weather.getSurfaceCurrentSpeedKn())).append(" 节");
            if (weather.getSurfaceCurrentSetDeg() != null) sb.append(" ").append(weather.getSurfaceCurrentSetDeg()).append("°");
            sb.append("，");
        }
        if (weather.getSeaState() != null) sb.append("海况 ").append(weather.getSeaState()).append(" 级。");

        return sb.toString().replaceAll("，$", "。");
    }

    private RiskLevel highestRiskLevel(AgentSnapshot snapshot) {
        if (snapshot.riskContext() == null || snapshot.riskContext().getTargets() == null) {
            return RiskLevel.SAFE;
        }
        return snapshot.riskContext().getTargets().stream()
                .map(LlmRiskTargetContext::getRiskLevel)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(RiskLevel.SAFE);
    }

    private long nonSafeCount(AgentSnapshot snapshot) {
        if (snapshot.riskContext() == null || snapshot.riskContext().getTargets() == null) {
            return 0L;
        }
        return snapshot.riskContext().getTargets().stream()
                .filter(t -> t.getRiskLevel() != null && t.getRiskLevel() != RiskLevel.SAFE)
                .count();
    }
}
