import type { ChatReplyPayload, ExplanationPayload, RiskLevel, SpeechMode } from './schema';

export type AiCenterMessageStatus = 'pending' | 'sent' | 'replied' | 'error';
export type AiCenterMessageType = 'chat_user' | 'chat_reply' | 'speech_request' | 'speech_transcript';
export type AiCenterRequestType = 'CHAT' | 'SPEECH';
export type AiCenterRole = 'user' | 'assistant';

export interface StoredLlmExplanation extends Pick<ExplanationPayload, 'event_id' | 'target_id' | 'risk_level' | 'provider' | 'text' | 'timestamp'> {
  conversation_id: string;
  message_id: string;
}

export interface AiCenterChatMessage {
  event_id: string;
  conversation_id: string;
  role: AiCenterRole;
  request_type?: AiCenterRequestType;
  speech_mode?: SpeechMode;
  audio_format?: string;
  transcript_language?: string;
  content: string;
  status: AiCenterMessageStatus;
  reply_to_event_id?: string;
  provider?: string;
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
  provider: string;
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
  provider: string;
  timestamp?: string;
}

export type AiSpeechEvent = LlmExplanationEvent | AiAssistantMessageEvent;

export interface NormalizedChatReply {
  message: AiCenterChatMessage;
  speechEvent: AiAssistantMessageEvent;
}

export interface RetryableChatMessage {
  conversation_id: string;
  request_type: AiCenterRequestType;
  content: string;
}

export type ChatReplyLike = Pick<
  ChatReplyPayload,
  'conversation_id' | 'event_id' | 'role' | 'content' | 'provider' | 'timestamp'
>;
