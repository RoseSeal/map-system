import type {
  ChatCapabilityPayload,
  ChatErrorPayload,
  ChatReplyPayload,
  ClearHistoryAckPayload,
  LlmProviderSelectionPayload,
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

export const chatCapabilityFixture: ChatCapabilityPayload = {
  event_id: 'capability-event-1',
  chat_available: true,
  agent_available: true,
  speech_transcription_available: true,
  disabled_reasons: {
    chat: null,
    agent: null,
    speech_transcription: null,
  },
  llm_providers: [
    {
      provider: 'gemini',
      display_name: 'Gemini',
      available: true,
      supported_tasks: ['explanation', 'chat', 'agent'],
      quota_status: 'UNKNOWN',
    },
    {
      provider: 'zhipu',
      display_name: 'Zhipu',
      available: true,
      supported_tasks: ['explanation', 'chat', 'agent'],
      degraded_tasks: ['agent'],
      quota_status: 'UNKNOWN',
    },
  ],
  effective_provider_selection: {
    explanation_provider: 'zhipu',
    chat_provider: 'gemini',
  },
  provider_selection_mutable: true,
  timestamp: '2026-04-15T12:14:00.000Z',
};

export const llmProviderSelectionAckFixture: LlmProviderSelectionPayload = {
  event_id: 'provider-selection-ack-1',
  reply_to_event_id: 'provider-selection-event-1',
  effective_provider_selection: {
    explanation_provider: 'gemini',
    chat_provider: 'zhipu',
  },
  timestamp: '2026-04-15T12:15:00.000Z',
};
