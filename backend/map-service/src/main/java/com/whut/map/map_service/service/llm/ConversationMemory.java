package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.properties.LlmProperties;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ConversationMemory {

    private final int maxMessages;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, ConversationEntry> store = new ConcurrentHashMap<>();

    public ConversationMemory(LlmProperties llmProperties) {
        this.maxMessages = Math.max(0, llmProperties.getConversationMaxTurns() * 2);
        this.ttlMillis = Math.max(0, llmProperties.getConversationTtlMinutes()) * 60_000L;
    }

    public void append(String conversationId, LlmChatMessage message) {
        ConversationEntry entry = store.get(conversationId);
        if (entry == null) {
            return;
        }
        entry.append(message, maxMessages);
    }

    public List<LlmChatMessage> getHistory(String conversationId) {
        ConversationEntry entry = store.get(conversationId);
        if (entry == null) {
            return List.of();
        }
        return entry.snapshot();
    }

    public ConversationPermit tryAcquire(String conversationId) {
        ConversationEntry entry = getOrCreateEntry(conversationId);
        return entry.tryAcquire() ? new ConversationPermit(entry::release) : null;
    }

    public boolean clear(String conversationId) {
        if (conversationId == null) {
            return false;
        }
        AtomicBoolean cleared = new AtomicBoolean(true);
        store.compute(conversationId, (key, entry) -> {
            if (entry == null) {
                return null;
            }
            if (!entry.retireIfIdle()) {
                cleared.set(false);
                return entry;
            }
            return null;
        });
        return cleared.get();
    }

    @Scheduled(fixedDelayString = "${llm.conversation-evict-interval-ms:60000}")
    public void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> entry.getValue().retireIfEvictable(now, ttlMillis));
    }

    private ConversationEntry getOrCreateEntry(String conversationId) {
        return store.computeIfAbsent(conversationId, key -> new ConversationEntry());
    }

    private static final class ConversationEntry {
        private final Semaphore permit = new Semaphore(1);
        private final Deque<LlmChatMessage> messages = new ArrayDeque<>();
        private volatile long lastAccessTimeMillis = System.currentTimeMillis();
        private boolean retired;

        synchronized boolean tryAcquire() {
            if (retired) {
                return false;
            }
            lastAccessTimeMillis = System.currentTimeMillis();
            return permit.tryAcquire();
        }

        synchronized void release() {
            lastAccessTimeMillis = System.currentTimeMillis();
            permit.release();
        }

        synchronized boolean retireIfIdle() {
            if (retired) {
                return true;
            }
            if (permit.availablePermits() <= 0) {
                return false;
            }
            retired = true;
            lastAccessTimeMillis = System.currentTimeMillis();
            return true;
        }

        synchronized boolean retireIfEvictable(long now, long ttlMillis) {
            if (retired) {
                return true;
            }
            if (permit.availablePermits() <= 0 || (now - lastAccessTimeMillis) < ttlMillis) {
                return false;
            }
            retired = true;
            return true;
        }

        synchronized void append(LlmChatMessage message, int maxMessages) {
            messages.addLast(message);
            lastAccessTimeMillis = System.currentTimeMillis();

            if (maxMessages <= 0) {
                messages.clear();
                return;
            }

            while (messages.size() > maxMessages) {
                messages.pollFirst();
                messages.pollFirst();
            }
        }

        synchronized List<LlmChatMessage> snapshot() {
            lastAccessTimeMillis = System.currentTimeMillis();
            return List.copyOf(messages);
        }
    }

    public static final class ConversationPermit implements AutoCloseable {
        private final Runnable releaseAction;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private ConversationPermit(Runnable releaseAction) {
            this.releaseAction = releaseAction;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                releaseAction.run();
            }
        }
    }
}
