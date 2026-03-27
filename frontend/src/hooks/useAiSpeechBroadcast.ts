import { useEffect } from 'react';
import { useAiCenterStore } from '../store/useAiCenterStore';
import { speechService } from '../services/speechService';
import { buildSpeechText, toLlmExplanationEvent } from '../utils/llmEventNormalizer';
import type { AiAssistantMessageEvent, AiCenterChatMessage } from '../types/aiCenter';

const WARNING_SPEECH_INTERVAL_MS = 15000;

export function useAiSpeechBroadcast() {
  useEffect(() => {
    const supported = speechService.isSupported();
    const store = useAiCenterStore.getState();
    store.setSpeechSupported(supported);
    if (supported) {
      speechService.init();
    }

    const unsubscribe = useAiCenterStore.subscribe(
      (state) => ({
        speechEnabled: state.speechEnabled,
        speechUnlocked: state.speechUnlocked,
        latestLlmExplanations: state.latestLlmExplanations,
        chatMessages: state.chatMessages,
      }),
      () => {
        if (!supported) {
          return;
        }

        const currentState = useAiCenterStore.getState();
        if (!currentState.speechEnabled || !currentState.speechUnlocked) {
          speechService.stop();
          return;
        }

        const explanationEvents = Object.values(currentState.latestLlmExplanations)
          .map((item) => toLlmExplanationEvent(item))
          .filter((item): item is NonNullable<typeof item> => Boolean(item));

        explanationEvents.forEach((event) => {
          const spokenText = currentState.spokenMessages[event.message_id];
          if (spokenText === event.text) {
            return;
          }

          const lastSpokenAt = currentState.lastSpokenAt[event.message_id] || 0;
          const isAlarm = event.risk_level === 'ALARM';
          const now = Date.now();

          if (!isAlarm && now - lastSpokenAt < WARNING_SPEECH_INTERVAL_MS) {
            return;
          }

          if (!isAlarm && speechService.isSpeaking()) {
            return;
          }

          const didSpeak = speechService.speak(buildSpeechText(event), {
            interrupt: isAlarm,
          });

          if (didSpeak) {
            useAiCenterStore.getState().markMessageSpoken(event.message_id, event.text);
          }
        });

        currentState.chatMessages
          .filter(isAssistantReply)
          .map<AiAssistantMessageEvent>((message) => ({
            kind: 'chat_reply',
            conversation_id: message.sequence_id,
            message_id: message.message_id,
            role: 'assistant',
            content: message.content,
            source: message.source || 'assistant',
            timestamp: message.timestamp,
          }))
          .forEach((event) => {
            if (currentState.spokenMessages[event.message_id] === event.content) {
              return;
            }

            const didSpeak = speechService.speak(buildSpeechText(event), {
              interrupt: false,
            });

            if (didSpeak) {
              useAiCenterStore.getState().markMessageSpoken(event.message_id, event.content);
            }
          });
      },
      { equalityFn: shallowSpeechStateEqual },
    );

    return () => {
      unsubscribe();
    };
  }, []);
}

function shallowSpeechStateEqual(
  prev: { speechEnabled: boolean; speechUnlocked: boolean; latestLlmExplanations: object; chatMessages: object },
  next: { speechEnabled: boolean; speechUnlocked: boolean; latestLlmExplanations: object; chatMessages: object },
): boolean {
  return prev.speechEnabled === next.speechEnabled
    && prev.speechUnlocked === next.speechUnlocked
    && prev.latestLlmExplanations === next.latestLlmExplanations
    && prev.chatMessages === next.chatMessages;
}

function isAssistantReply(message: AiCenterChatMessage): boolean {
  return message.role === 'assistant' && message.message_type === 'chat_reply' && Boolean(message.content.trim());
}
