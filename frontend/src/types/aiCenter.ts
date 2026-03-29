import type { ChatInputType, ChatMode, ChatReplyPayload, ChatRole, RiskExplanation, RiskLevel } from './schema';

export type AiCenterMessageStatus = 'pending' | 'sent' | 'replied' | 'error';
export type AiCenterMessageType = 'chat_user' | 'chat_reply';

export interface StoredLlmExplanation extends RiskExplanation {
  conversation_id: string;
  message_id: string;
  target_id: string;
  risk_level: RiskLevel;
  timestamp?: string;
}

export interface AiCenterChatMessage {
  message_id: string;
  sequence_id: string;
  role: ChatRole;
  input_type?: ChatInputType;
  chat_mode?: ChatMode;
  audio_format?: string;
  transcript_language?: string;
  content: string;
  status: AiCenterMessageStatus;
  reply_to_message_id?: string;
  source?: string;
  timestamp?: string;
  error_code?: string;
  error_message?: string;
  message_type: AiCenterMessageType;
}

export interface LlmExplanationEvent {
  kind: 'risk_explanation';
  conversation_id: string;
  message_id: string;
  target_id: string;
  source: string;
  text: string;
  risk_level: RiskLevel;
  timestamp?: string;
}

export interface AiAssistantMessageEvent {
  kind: 'chat_reply';
  conversation_id: string;
  message_id: string;
  role: 'assistant';
  content: string;
  source: string;
  timestamp?: string;
}

export type AiSpeechEvent = LlmExplanationEvent | AiAssistantMessageEvent;

export interface NormalizedChatReply {
  message: AiCenterChatMessage;
  speechEvent: AiAssistantMessageEvent;
}

export interface RetryableChatMessage {
  sequence_id: string;
  input_type: ChatInputType;
  content: string;
}

export type ChatReplyLike = Pick<
  ChatReplyPayload,
  'sequence_id' | 'message_id' | 'role' | 'content' | 'source' | 'timestamp'
>;
