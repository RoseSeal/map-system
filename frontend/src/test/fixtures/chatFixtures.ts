import type {
  ChatErrorPayload,
  ChatReplyPayload,
  ClearHistoryAckPayload,
  SpeechTranscriptPayload,
} from '../../types/schema';

export const chatReplyFixture: ChatReplyPayload = {
  event_id: 'assistant-event-1',
  conversation_id: 'conversation-1',
  reply_to_event_id: 'user-event-1',
  role: 'assistant',
  content: 'Suggested action: reduce speed and alter course 10 degrees starboard.',
  provider: 'gemini',
  timestamp: '2026-04-15T12:10:00.000Z',
};

export const speechTranscriptFixture: SpeechTranscriptPayload = {
  event_id: 'transcript-event-1',
  conversation_id: 'conversation-1',
  reply_to_event_id: 'voice-event-1',
  transcript: 'Assess closest target and provide collision risk.',
  language: 'en',
  timestamp: '2026-04-15T12:11:00.000Z',
};

export const chatErrorFixture: ChatErrorPayload = {
  event_id: 'chat-error-1',
  connection: 'chat',
  error_code: 'LLM_TIMEOUT',
  error_message: 'Assistant response timed out',
  reply_to_event_id: 'user-event-1',
  timestamp: '2026-04-15T12:12:00.000Z',
};

export const chatGlobalErrorFixture: ChatErrorPayload = {
  event_id: 'chat-error-2',
  connection: 'chat',
  error_code: 'CHAT_CHANNEL_UNAVAILABLE',
  error_message: 'Chat service unavailable',
  reply_to_event_id: null,
  timestamp: '2026-04-15T12:12:30.000Z',
};

export const clearHistoryAckFixture: ClearHistoryAckPayload = {
  event_id: 'clear-history-ack-1',
  conversation_id: 'conversation-1',
  reply_to_event_id: 'clear-history-event-1',
  timestamp: '2026-04-15T12:13:00.000Z',
};
