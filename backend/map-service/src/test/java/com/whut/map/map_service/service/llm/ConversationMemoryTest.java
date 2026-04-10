package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.properties.LlmProperties;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryTest {

    @Test
    void appendMaintainsSlidingWindowByTurn() {
        ConversationMemory memory = new ConversationMemory(properties(2, 30));
        String conversationId = "conversation-1";
        ConversationMemory.ConversationPermit permit = memory.tryAcquire(conversationId);
        permit.close();

        memory.append(conversationId, new LlmChatMessage(ChatRole.USER, "u1"));
        memory.append(conversationId, new LlmChatMessage(ChatRole.ASSISTANT, "a1"));
        memory.append(conversationId, new LlmChatMessage(ChatRole.USER, "u2"));
        memory.append(conversationId, new LlmChatMessage(ChatRole.ASSISTANT, "a2"));
        memory.append(conversationId, new LlmChatMessage(ChatRole.USER, "u3"));
        memory.append(conversationId, new LlmChatMessage(ChatRole.ASSISTANT, "a3"));

        assertThat(memory.getHistory(conversationId)).containsExactly(
                new LlmChatMessage(ChatRole.USER, "u2"),
                new LlmChatMessage(ChatRole.ASSISTANT, "a2"),
                new LlmChatMessage(ChatRole.USER, "u3"),
                new LlmChatMessage(ChatRole.ASSISTANT, "a3")
        );
    }

    @Test
    void getHistoryReturnsImmutableSnapshot() {
        ConversationMemory memory = new ConversationMemory(properties(2, 30));
        String conversationId = "conversation-1";
        ConversationMemory.ConversationPermit permit = memory.tryAcquire(conversationId);
        permit.close();
        memory.append(conversationId, new LlmChatMessage(ChatRole.USER, "u1"));
        memory.append(conversationId, new LlmChatMessage(ChatRole.ASSISTANT, "a1"));

        List<LlmChatMessage> history = memory.getHistory(conversationId);

        assertThat(history).containsExactly(
                new LlmChatMessage(ChatRole.USER, "u1"),
                new LlmChatMessage(ChatRole.ASSISTANT, "a1")
        );
        assertThat(history).isUnmodifiable();
    }

    @Test
    void clearDropsConversationAndPreventsRevivalFromLateAppend() {
        ConversationMemory memory = new ConversationMemory(properties(2, 30));
        String conversationId = "conversation-1";
        ConversationMemory.ConversationPermit permit = memory.tryAcquire(conversationId);
        permit.close();
        memory.append(conversationId, new LlmChatMessage(ChatRole.USER, "u1"));
        memory.append(conversationId, new LlmChatMessage(ChatRole.ASSISTANT, "a1"));

        assertThat(memory.clear(conversationId)).isTrue();
        memory.append(conversationId, new LlmChatMessage(ChatRole.USER, "late"));

        assertThat(memory.getHistory(conversationId)).isEmpty();
    }

    @Test
    void clearRejectsBusyConversationAndKeepsExistingPermitExclusive() {
        ConversationMemory memory = new ConversationMemory(properties(2, 30));
        String conversationId = "conversation-1";
        ConversationMemory.ConversationPermit permit = memory.tryAcquire(conversationId);

        assertThat(memory.clear(conversationId)).isFalse();
        assertThat(memory.tryAcquire(conversationId)).isNull();

        permit.close();

        assertThat(memory.clear(conversationId)).isTrue();
        assertThat(memory.tryAcquire(conversationId)).isNotNull();
    }

    @Test
    void evictExpiredRemovesInactiveConversation() {
        ConversationMemory memory = new ConversationMemory(properties(2, 0));
        String conversationId = "conversation-1";
        ConversationMemory.ConversationPermit permit = memory.tryAcquire(conversationId);
        permit.close();
        memory.append(conversationId, new LlmChatMessage(ChatRole.USER, "u1"));
        memory.append(conversationId, new LlmChatMessage(ChatRole.ASSISTANT, "a1"));

        memory.evictExpired();

        assertThat(memory.getHistory(conversationId)).isEmpty();
    }

    @Test
    void evictExpiredSkipsConversationWithInflightRequest() {
        ConversationMemory memory = new ConversationMemory(properties(2, 0));
        String conversationId = "conversation-1";
        ConversationMemory.ConversationPermit permit = memory.tryAcquire(conversationId);
        memory.append(conversationId, new LlmChatMessage(ChatRole.USER, "u1"));
        memory.append(conversationId, new LlmChatMessage(ChatRole.ASSISTANT, "a1"));

        memory.evictExpired();

        assertThat(memory.getHistory(conversationId)).containsExactly(
                new LlmChatMessage(ChatRole.USER, "u1"),
                new LlmChatMessage(ChatRole.ASSISTANT, "a1")
        );

        permit.close();
        memory.evictExpired();
        assertThat(memory.getHistory(conversationId)).isEmpty();
    }

    @Test
    void evictExpiredIsDisabledWhenTtlIsNegative() {
        ConversationMemory memory = new ConversationMemory(properties(2, -1));
        String conversationId = "conversation-1";
        ConversationMemory.ConversationPermit permit = memory.tryAcquire(conversationId);
        permit.close();
        memory.append(conversationId, new LlmChatMessage(ChatRole.USER, "u1"));
        memory.append(conversationId, new LlmChatMessage(ChatRole.ASSISTANT, "a1"));

        memory.evictExpired();

        assertThat(memory.getHistory(conversationId)).containsExactly(
                new LlmChatMessage(ChatRole.USER, "u1"),
                new LlmChatMessage(ChatRole.ASSISTANT, "a1")
        );
    }

    private LlmProperties properties(int maxTurns, long ttlMinutes) {
        LlmProperties properties = new LlmProperties();
        properties.setConversationMaxTurns(maxTurns);
        properties.setConversationTtlMinutes(ttlMinutes);
        return properties;
    }
}
