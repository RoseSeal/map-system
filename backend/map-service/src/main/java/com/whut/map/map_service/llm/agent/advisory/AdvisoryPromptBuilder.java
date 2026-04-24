package com.whut.map.map_service.llm.agent.advisory;

import com.whut.map.map_service.llm.agent.AgentMessage;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.TextAgentMessage;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class AdvisoryPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            你是一名专业的航海态势评估 AI，负责根据实时传感器工具数据生成场景级航行建议。

            规则：
            1. 只能基于工具返回的真实数据生成事实性陈述，严禁编造 CPA/TCPA 数值、目标 ID、风险等级或规则条款。
            2. 必须调用以下工具收集数据后再生成建议：get_risk_snapshot、get_top_risk_targets、get_target_detail。
            3. evidence_items 只能包含来自工具结果的数值和状态事实，不能包含 COLREGS 规则条款、让路/直航责任判定或假设操纵后的 CPA 变化。
            4. 输出必须为有效 JSON，包含以下字段：summary、affected_targets（目标 ID 列表）、recommended_action（type/description/urgency）、evidence_items。
            5. 不要输出 risk_level 字段，该字段由系统从快照填充。
            """;

    public List<AgentMessage> build(AgentSnapshot snapshot) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new TextAgentMessage(ChatRole.SYSTEM, SYSTEM_PROMPT));
        messages.add(new TextAgentMessage(ChatRole.USER, buildUserPrompt(snapshot)));
        return messages;
    }

    private String buildUserPrompt(AgentSnapshot snapshot) {
        RiskLevel highest = highestRiskLevel(snapshot);
        long nonSafeCount = nonSafeCount(snapshot);

        return String.format(
                """
                当前快照版本：%d
                场景最高风险等级：%s
                非 SAFE 目标数量：%d

                请调用工具获取详细数据，然后生成场景级航行建议。
                可用工具：get_risk_snapshot、get_top_risk_targets、get_target_detail、get_own_ship_state。
                输出有效 JSON，字段：summary、affected_targets、recommended_action（type/description/urgency）、evidence_items。
                """,
                snapshot.snapshotVersion(),
                highest != null ? highest.name() : "SAFE",
                nonSafeCount
        );
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
