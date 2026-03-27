import type {
  ChatErrorPayload,
  ChatInputType,
  ChatReplyPayload,
  RiskExplanation,
  RiskLevel,
  RiskObject,
  Target,
} from '../types/schema';
import type {
  AiAssistantMessageEvent,
  AiCenterChatMessage,
  LlmExplanationEvent,
  NormalizedChatReply,
  StoredLlmExplanation,
} from '../types/aiCenter';

export function isLlmSource(source?: string | null): boolean {
  const normalized = (source || '').toLowerCase();
  return Boolean(
    normalized === 'llm'
    || normalized.includes('llm')
    || normalized.includes('ai')
    || normalized.includes('model')
    || normalized.includes('gpt'),
  );
}

export function normalizeLlmExplanation(
  riskObject: RiskObject,
  target: Target,
  explanation?: RiskExplanation | null,
): StoredLlmExplanation | null {
  const text = explanation?.text?.trim();
  if (!text || !isLlmSource(explanation?.source)) {
    return null;
  }

  const targetId = target.id;
  const conversationId = buildLlmConversationKey(targetId);
  const messageId = buildLlmMessageKey(conversationId, targetId, text);

  return {
    ...explanation,
    text,
    conversation_id: conversationId,
    message_id: messageId,
    target_id: targetId,
    risk_level: target.risk_assessment.risk_level,
    timestamp: riskObject.timestamp,
  };
}

export function buildLlmMessageKey(conversationId: string, targetId: string, text: string): string {
  return `${conversationId}::${targetId}::${text}`;
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
    source: explanation.source || 'llm',
    text: explanation.text,
    risk_level: explanation.risk_level,
    timestamp: explanation.timestamp,
  };
}

export function normalizeChatReply(payload: ChatReplyPayload): NormalizedChatReply | null {
  const content = payload.content?.trim();
  if (!content) {
    return null;
  }

  const message: AiCenterChatMessage = {
    message_id: payload.message_id,
    sequence_id: payload.sequence_id,
    role: 'assistant',
    content,
    status: 'replied',
    reply_to_message_id: payload.reply_to_message_id,
    source: payload.source,
    timestamp: payload.timestamp,
    message_type: 'chat_reply',
  };

  const speechEvent: AiAssistantMessageEvent = {
    kind: 'chat_reply',
    conversation_id: payload.sequence_id,
    message_id: payload.message_id,
    role: 'assistant',
    content,
    source: payload.source || 'assistant',
    timestamp: payload.timestamp,
  };

  return { message, speechEvent };
}

export function createUserChatMessage(
  sequenceId: string,
  content: string,
  inputType: ChatInputType = 'TEXT',
): AiCenterChatMessage {
  const text = content.trim();
  return {
    message_id: createChatMessageId('user'),
    sequence_id: sequenceId,
    role: 'user',
    input_type: inputType,
    content: text,
    status: 'pending',
    timestamp: new Date().toISOString(),
    message_type: 'chat_user',
  };
}

export function createChatSessionId(): string {
  return `conversation-${createCompactId()}`;
}

export function createChatMessageId(prefix: string = 'message'): string {
  return `${prefix}-${createCompactId()}`;
}

export function normalizeChatErrorMessage(error: ChatErrorPayload): { messageId?: string; errorText: string } {
  return {
    messageId: error.reply_to_message_id,
    errorText: error.error_message,
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

function createCompactId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
}
