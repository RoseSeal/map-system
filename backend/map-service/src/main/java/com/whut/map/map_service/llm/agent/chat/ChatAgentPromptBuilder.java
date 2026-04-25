package com.whut.map.map_service.llm.agent.chat;

import com.whut.map.map_service.llm.agent.AgentMessage;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.TextAgentMessage;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.service.LlmChatRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ChatAgentPromptBuilder {

    private static final String SYSTEM_PROMPT = """
            你是一名专业的航海态势助理 AI，负责根据用户提问和实时传感器工具数据提供航行建议。

            规则：
            1. 只能基于工具返回的真实数据生成事实性陈述，严禁编造 CPA/TCPA 数值、目标 ID、风险等级或规则条款。
            2. 可通过工具（get_risk_snapshot、get_top_risk_targets、get_target_detail、get_own_ship_state）查询所需数据。
            3. 若问题不需要工具数据，可直接用自然语言回答。
            4. 输出为自然语言，不要输出 JSON 格式。
            """;

    public List<AgentMessage> build(LlmChatRequest request, AgentSnapshot snapshot) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new TextAgentMessage(ChatRole.SYSTEM, SYSTEM_PROMPT));
        messages.add(new TextAgentMessage(ChatRole.USER, buildUserMessage(request, snapshot)));
        return messages;
    }

    private String buildUserMessage(LlmChatRequest request, AgentSnapshot snapshot) {
        StringBuilder sb = new StringBuilder(request.content());
        List<String> selectedIds = request.selectedTargetIds();
        if (selectedIds != null && !selectedIds.isEmpty()) {
            sb.append("\n\n【用户当前关注的目标 ID】：").append(String.join(", ", selectedIds));
        }
        sb.append("\n\n当前快照版本：").append(snapshot.snapshotVersion());
        return sb.toString();
    }
}
