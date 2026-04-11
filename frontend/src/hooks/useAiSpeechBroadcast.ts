import { useEffect } from 'react';
import { useAiCenterStore } from '../store/useAiCenterStore';
import { useRiskStore } from '../store/useRiskStore';
import { speechService } from '../services/speechService';
import { buildSpeechText, normalizeLlmExplanation, toLlmExplanationEvent } from '../utils/llmEventNormalizer';
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

    const processSpeech = () => {
      if (!supported) {
        return;
      }

      const aiState = useAiCenterStore.getState();
      if (!aiState.speechEnabled || !aiState.speechUnlocked) {
        speechService.stop();
        return;
      }

      const riskState = useRiskStore.getState();
      const explanationEvents = Object.values(riskState.explanationsByTargetId)
        .map((item) => normalizeLlmExplanation(item))
        .map((item) => (item ? toLlmExplanationEvent(item) : null))
        .filter((item): item is NonNullable<typeof item> => Boolean(item));

      explanationEvents.forEach((event) => {
        const spokenText = aiState.spokenMessages[event.message_id];
        if (spokenText === event.text) {
          return;
        }

        const lastSpokenAt = aiState.lastSpokenAt[event.conversation_id] || 0;
        const isAlarm = event.risk_level === 'ALARM';
        const now = Date.now();

        if (!isAlarm && now - lastSpokenAt < WARNING_SPEECH_INTERVAL_MS) {
          return;
        }

        if (!isAlarm && speechService.isSpeaking()) {
          useAiCenterStore.getState().markMessageSpoken(event.conversation_id, event.text);
          return;
        }

        const didSpeak = speechService.speak(buildSpeechText(event), {
          interrupt: isAlarm,
        });

        if (didSpeak) {
          useAiCenterStore.getState().markMessageSpoken(event.message_id, event.text);
          useAiCenterStore.getState().markMessageSpoken(event.conversation_id, event.text);
        }
      });

      aiState.chatMessages
        .filter(isAssistantReply)
        .map<AiAssistantMessageEvent>((message) => ({
          kind: 'chat_reply',
          conversation_id: message.conversation_id,
          message_id: message.event_id,
          role: 'assistant',
          content: message.content,
          provider: message.provider || 'assistant',
          timestamp: message.timestamp,
        }))
        .forEach((event) => {
          if (aiState.spokenMessages[event.message_id] === event.content) {
            return;
          }

          const didSpeak = speechService.speak(buildSpeechText(event), {
            interrupt: false,
          });

          if (didSpeak) {
            useAiCenterStore.getState().markMessageSpoken(event.message_id, event.content);
          }
        });
    };

    const unsubscribeAi = useAiCenterStore.subscribe(
      (state) => ({
        speechEnabled: state.speechEnabled,
        speechUnlocked: state.speechUnlocked,
        chatMessages: state.chatMessages,
      }),
      processSpeech,
      { equalityFn: shallowAiSpeechStateEqual },
    );

    const unsubscribeRisk = useRiskStore.subscribe(
      (state) => state.explanationsByTargetId,
      processSpeech,
    );

    return () => {
      unsubscribeAi();
      unsubscribeRisk();
    };
  }, []);
}

function shallowAiSpeechStateEqual(
  prev: { speechEnabled: boolean; speechUnlocked: boolean; chatMessages: object },
  next: { speechEnabled: boolean; speechUnlocked: boolean; chatMessages: object },
): boolean {
  return prev.speechEnabled === next.speechEnabled
    && prev.speechUnlocked === next.speechUnlocked
    && prev.chatMessages === next.chatMessages;
}

function isAssistantReply(message: AiCenterChatMessage): boolean {
  return message.role === 'assistant' && message.message_type === 'chat_reply' && Boolean(message.content.trim());
}
