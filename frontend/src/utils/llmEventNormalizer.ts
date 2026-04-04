import type {
  ExplanationPayload,
  RiskLevel,
} from '../types/schema';
import type {
  AiAssistantMessageEvent,
  LlmExplanationEvent,
  StoredLlmExplanation,
} from '../types/aiCenter';

export function isLlmProvider(provider?: string | null): boolean {
  return Boolean(provider && provider.trim());
}

export function normalizeLlmExplanation(explanation: ExplanationPayload): StoredLlmExplanation | null {
  const text = explanation.text.trim();
  if (!text || !isLlmProvider(explanation.provider)) {
    return null;
  }

  return {
    event_id: explanation.event_id,
    conversation_id: buildLlmConversationKey(explanation.target_id),
    message_id: buildLlmMessageKey(explanation.target_id, explanation.event_id),
    target_id: explanation.target_id,
    risk_level: explanation.risk_level,
    provider: explanation.provider,
    text,
    timestamp: explanation.timestamp,
  };
}

export function buildLlmMessageKey(targetId: string, eventId: string): string {
  return `llm-explanation::${targetId}::${eventId}`;
}

export function buildLlmConversationKey(targetId: string): string {
  return `llm-explanation::${targetId}`;
}

export function toLlmExplanationEvent(explanation: StoredLlmExplanation): LlmExplanationEvent | null {
  if (!explanation.text || !explanation.target_id || !explanation.risk_level) {
    return null;
  }

  return {
    kind: 'risk_explanation',
    conversation_id: explanation.conversation_id,
    message_id: explanation.message_id,
    target_id: explanation.target_id,
    provider: explanation.provider || 'fallback',
    text: explanation.text,
    risk_level: explanation.risk_level,
    timestamp: explanation.timestamp,
  };
}

export function buildSpeechText(event: LlmExplanationEvent | AiAssistantMessageEvent): string {
  if (event.kind === 'risk_explanation') {
    return sanitizeSpeechText(
      `目标 ${event.target_id}，风险等级 ${translateRiskLevel(event.risk_level)}。${event.text}`,
    );
  }

  return sanitizeSpeechText(event.content);
}

export function sanitizeSpeechText(text: string): string {
  return text
    .replace(/\s+/g, ' ')
    .replace(/[【】<>]/g, ' ')
    .replace(/[“”]/g, ' ')
    .replace(/[()（）]/g, '，')
    .replace(/[:：]/g, '，')
    .replace(/[;；]/g, '，')
    .replace(/[\[\]{}]/g, ' ')
    .trim()
    .slice(0, 140);
}

export function translateRiskLevel(level: RiskLevel): string {
  switch (level) {
    case 'SAFE':
      return '安全';
    case 'CAUTION':
      return '注意';
    case 'WARNING':
      return '警告';
    case 'ALARM':
      return '警报';
    default:
      return level;
  }
}
