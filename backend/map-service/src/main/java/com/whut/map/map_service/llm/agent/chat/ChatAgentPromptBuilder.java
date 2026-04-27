package com.whut.map.map_service.llm.agent.chat;

import com.whut.map.map_service.llm.agent.AgentMessage;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.TextAgentMessage;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.prompt.PromptScene;
import com.whut.map.map_service.llm.prompt.PromptTemplateService;
import com.whut.map.map_service.llm.service.LlmChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ChatAgentPromptBuilder {

    private final PromptTemplateService promptTemplateService;

    public List<AgentMessage> build(LlmChatRequest request, AgentSnapshot snapshot) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(new TextAgentMessage(ChatRole.SYSTEM, promptTemplateService.getSystemPrompt(PromptScene.AGENT_CHAT)));
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
