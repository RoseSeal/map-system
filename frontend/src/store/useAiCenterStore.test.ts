import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  chatCapabilityFixture,
  chatErrorFixture,
  chatGlobalErrorFixture,
  chatReplyFixture,
  llmProviderSelectionAckFixture,
  speechTranscriptFixture,
} from '../test/fixtures';
import { CHAT_CONFIG } from '../config/constants';

const chatSubscribers = vi.hoisted(() => ({
  onChatReply: undefined as ((payload: unknown) => void) | undefined,
  onSpeechTranscript: undefined as ((payload: unknown) => void) | undefined,
  onError: undefined as ((payload: unknown) => void) | undefined,
  onClearHistoryAck: undefined as ((payload: unknown) => void) | undefined,
  onConnectionStateChange: undefined as ((state: string) => void) | undefined,
  onCapabilityState: undefined as ((state: string, payload: unknown) => void) | undefined,
  onAgentStep: undefined as ((payload: unknown) => void) | undefined,
  onExpiredExplanationsCleared: undefined as ((payload: unknown) => void) | undefined,
  onLlmProviderSelection: undefined as ((payload: unknown) => void) | undefined,
}));

const chatWsServiceMock = vi.hoisted(() => ({
  send: vi.fn(),
  sendClearHistory: vi.fn(),
  onChatReply: vi.fn((cb: (payload: unknown) => void) => {
    chatSubscribers.onChatReply = cb;
    return vi.fn();
  }),
  onSpeechTranscript: vi.fn((cb: (payload: unknown) => void) => {
    chatSubscribers.onSpeechTranscript = cb;
    return vi.fn();
  }),
  onError: vi.fn((cb: (payload: unknown) => void) => {
    chatSubscribers.onError = cb;
    return vi.fn();
  }),
  onClearHistoryAck: vi.fn((cb: (payload: unknown) => void) => {
    chatSubscribers.onClearHistoryAck = cb;
    return vi.fn();
  }),
  onConnectionStateChange: vi.fn((cb: (state: any) => void) => {
    chatSubscribers.onConnectionStateChange = cb;
    return vi.fn();
  }),
  onCapabilityState: vi.fn((cb: (state: any, payload: any) => void) => {
    chatSubscribers.onCapabilityState = cb;
    return vi.fn();
  }),
  onAgentStep: vi.fn((cb: (payload: unknown) => void) => {
    chatSubscribers.onAgentStep = cb;
    return vi.fn();
  }),
  onExpiredExplanationsCleared: vi.fn((cb: (payload: unknown) => void) => {
    chatSubscribers.onExpiredExplanationsCleared = cb;
    return vi.fn();
  }),
  onLlmProviderSelection: vi.fn((cb: (payload: unknown) => void) => {
    chatSubscribers.onLlmProviderSelection = cb;
    return vi.fn();
  }),
}));

vi.mock('../services/chatWsService', () => ({
  chatWsService: chatWsServiceMock,
}));

import { useRiskStore } from './useRiskStore';
import { useAiCenterStore } from './useAiCenterStore';

describe('useAiCenterStore', () => {
  beforeEach(() => {
    vi.useRealTimers();
    useRiskStore.getState().reset();
    useAiCenterStore.getState().reset();
    chatWsServiceMock.send.mockImplementation(() => 'generated-event-id');
    useAiCenterStore.getState().setChatCapabilityState('ready', chatCapabilityFixture);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('sendTextMessage rejects empty text and pending conversation requests', () => {
    const store = useAiCenterStore.getState();

    expect(store.sendTextMessage('   ')).toBe(false);
    expect(chatWsServiceMock.send).not.toHaveBeenCalled();

    chatWsServiceMock.send.mockReturnValueOnce('user-event-1');
    expect(store.sendTextMessage('First message')).toBe(true);
    expect(store.sendTextMessage('Second message')).toBe(false);
    expect(chatWsServiceMock.send).toHaveBeenCalledTimes(1);
  });

  it('sendTextMessage sends CHAT payload and includes selected target ids', () => {
    const riskStore = useRiskStore.getState();
    const aiStore = useAiCenterStore.getState();

    riskStore.selectTarget('TGT-ALARM');
    chatWsServiceMock.send.mockReturnValueOnce('user-event-1');

    expect(aiStore.sendTextMessage('  Assess closest risk  ')).toBe(true);

    const conversationId = useAiCenterStore.getState().conversationId;
    expect(chatWsServiceMock.send).toHaveBeenCalledWith('CHAT', {
      conversation_id: conversationId,
      content: 'Assess closest risk',
      agent_mode: 'CHAT',
      selected_target_ids: ['TGT-ALARM'],
    });

    const state = useAiCenterStore.getState();
    expect(state.chatInput).toBe('');
    expect(state.chatMessages).toHaveLength(1);
    expect(state.chatMessages[0].request_type).toBe('CHAT');
    expect(state.pendingChatEventIds['user-event-1']).toBe(true);
  });

  it('sendTextMessage returns false when websocket send fails', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce(null);

    expect(store.sendTextMessage('message')).toBe(false);
    expect(useAiCenterStore.getState().chatMessages).toHaveLength(0);
  });

  it('setProviderSelection keeps effective value until ack and applies selection on ack', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce('provider-selection-event-1');

    store.setProviderSelection({ chat_provider: 'zhipu' });

    expect(chatWsServiceMock.send).toHaveBeenCalledWith('SET_LLM_PROVIDER_SELECTION', {
      chat_provider: 'zhipu',
    });

    let state = useAiCenterStore.getState();
    expect(state.providerSelection?.chat_provider).toBe('gemini');
    expect(state.providerSelectionPending).toBe(true);

    store.applyProviderSelectionAck({
      ...llmProviderSelectionAckFixture,
      reply_to_event_id: 'provider-selection-event-1',
      effective_provider_selection: {
        explanation_provider: 'zhipu',
        chat_provider: 'zhipu',
      },
    });

    state = useAiCenterStore.getState();
    expect(state.providerSelection?.chat_provider).toBe('zhipu');
    expect(state.providerSelectionPending).toBe(false);
    expect(state.providerSelectionError).toBeNull();
  });

  it('provider selection error rolls back pending selection', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce('provider-selection-event-2');

    store.setProviderSelection({ explanation_provider: 'gemini' });
    expect(useAiCenterStore.getState().providerSelectionPending).toBe(true);

    store.appendChatError({
      ...chatErrorFixture,
      reply_to_event_id: 'provider-selection-event-2',
      error_message: 'provider not available',
      error_code: 'INVALID_CHAT_REQUEST',
    });

    const state = useAiCenterStore.getState();
    expect(state.providerSelectionPending).toBe(false);
    expect(state.providerSelection?.explanation_provider).toBe('zhipu');
    expect(state.providerSelectionError).toBe('provider not available');
  });

  it('sendSpeechMessage validates input and handles preview vs direct mode', () => {
    const store = useAiCenterStore.getState();

    expect(store.sendSpeechMessage({ audioData: ' ', audioFormat: 'pcm', mode: 'preview' })).toBe(false);
    expect(store.sendSpeechMessage({ audioData: 'abc', audioFormat: ' ', mode: 'preview' })).toBe(false);

    chatWsServiceMock.send.mockReturnValueOnce('voice-event-preview');
    expect(store.sendSpeechMessage({
      audioData: '  base64-audio  ',
      audioFormat: 'pcm16',
      mode: 'preview',
    })).toBe(true);

    let state = useAiCenterStore.getState();
    expect(state.chatMessages).toHaveLength(0);
    expect(state.pendingChatEventIds['voice-event-preview']).toBe(true);
    expect(state.voiceCaptureState).toBe('transcribing');
    expect(state.activeVoiceEventId).toBe('voice-event-preview');
    expect(state.activeVoiceMode).toBe('preview');

    useAiCenterStore.getState().reset();
    chatWsServiceMock.send.mockReturnValueOnce('voice-event-direct');

    expect(useAiCenterStore.getState().sendSpeechMessage({
      audioData: 'base64-audio',
      audioFormat: 'pcm16',
      mode: 'direct',
    })).toBe(true);

    state = useAiCenterStore.getState();
    expect(state.chatMessages).toHaveLength(1);
    expect(state.chatMessages[0].request_type).toBe('SPEECH');
    expect(state.chatMessages[0].message_type).toBe('speech_request');
    expect(state.voiceCaptureState).toBe('transcribing');
    expect(state.activeVoiceEventId).toBe('voice-event-direct');
  });

  it('appendSpeechTranscript updates input in preview mode', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce('voice-event-1');

    store.sendSpeechMessage({
      audioData: 'base64-audio',
      audioFormat: 'pcm16',
      mode: 'preview',
    });

    const conversationId = useAiCenterStore.getState().conversationId;
    store.appendSpeechTranscript({
      ...speechTranscriptFixture,
      conversation_id: conversationId,
      reply_to_event_id: 'voice-event-1',
      transcript: 'preview transcript',
    });

    const state = useAiCenterStore.getState();
    expect(state.chatInput).toBe('preview transcript');
    expect(state.chatMessages).toHaveLength(0);
    expect(state.pendingChatEventIds['voice-event-1']).toBe(false);
  });

  it('appendSpeechTranscript updates target message in direct mode', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce('voice-event-2');

    store.sendSpeechMessage({
      audioData: 'base64-audio',
      audioFormat: 'pcm16',
      mode: 'direct',
    });

    const conversationId = useAiCenterStore.getState().conversationId;
    store.appendSpeechTranscript({
      ...speechTranscriptFixture,
      conversation_id: conversationId,
      reply_to_event_id: 'voice-event-2',
      transcript: 'direct transcript',
    });

    const state = useAiCenterStore.getState();
    expect(state.chatMessages).toHaveLength(1);
    expect(state.chatMessages[0].content).toBe('direct transcript');
    expect(state.chatMessages[0].status).toBe('sent');
    expect(state.chatMessages[0].transcript_language).toBe('en');
  });

  it('appendChatReply appends assistant message and deduplicates repeated events', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce('user-event-1');

    store.sendTextMessage('Need recommendation');
    const conversationId = useAiCenterStore.getState().conversationId;

    const payload = {
      ...chatReplyFixture,
      event_id: 'assistant-event-1',
      conversation_id: conversationId,
      reply_to_event_id: 'user-event-1',
    };

    store.appendChatReply(payload);
    store.appendChatReply(payload);

    const state = useAiCenterStore.getState();
    const assistantMessages = state.chatMessages.filter((message) => message.role === 'assistant');
    expect(assistantMessages).toHaveLength(1);

    const userMessage = state.chatMessages.find((message) => message.event_id === 'user-event-1');
    expect(userMessage?.status).toBe('replied');
    expect(state.pendingChatEventIds['user-event-1']).toBe(false);
  });

  it('appendChatReply ignores payload from a different conversation', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce('user-event-2');

    store.sendTextMessage('Current conversation message');
    const before = useAiCenterStore.getState().chatMessages.length;

    store.appendChatReply({
      ...chatReplyFixture,
      event_id: 'assistant-event-x',
      conversation_id: 'another-conversation',
      reply_to_event_id: 'user-event-2',
    });

    expect(useAiCenterStore.getState().chatMessages).toHaveLength(before);
  });

  it('confirmEditingLastUserMessage sends edit payload without appending a new user message', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send
      .mockReturnValueOnce('user-event-1')
      .mockReturnValueOnce('edit-event-1');

    store.sendTextMessage('Original question');
    const conversationId = useAiCenterStore.getState().conversationId;
    store.appendChatReply({
      ...chatReplyFixture,
      event_id: 'assistant-event-1',
      conversation_id: conversationId,
      reply_to_event_id: 'user-event-1',
    });

    expect(store.startEditingLastUserMessage()).toBe(true);
    store.updateEditingDraft('Edited question');

    expect(store.confirmEditingLastUserMessage()).toBe(true);
    expect(chatWsServiceMock.send).toHaveBeenLastCalledWith('CHAT', {
      conversation_id: conversationId,
      content: 'Edited question',
      agent_mode: 'CHAT',
      edit_last_user_message: true,
    });

    const state = useAiCenterStore.getState();
    expect(state.chatMessages).toHaveLength(2);
    expect(state.chatMessages[0]).toMatchObject({
      content: 'Edited question',
      status: 'pending',
    });
    expect(state.chatMessages[1]).toMatchObject({
      content: '正在重新生成回复...',
      status: 'pending',
    });
    expect(state.editingMessageEventId).toBeNull();
    expect(state.editingSubmitEventId).toBe('edit-event-1');
    expect(state.pendingChatEventIds['edit-event-1']).toBe(true);
  });

  it('appendChatReply replaces the last turn after an edit submit succeeds', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send
      .mockReturnValueOnce('user-event-1')
      .mockReturnValueOnce('edit-event-1');

    store.sendTextMessage('Original question');
    const conversationId = useAiCenterStore.getState().conversationId;
    store.appendChatReply({
      ...chatReplyFixture,
      event_id: 'assistant-event-1',
      conversation_id: conversationId,
      reply_to_event_id: 'user-event-1',
      content: 'Original answer',
    });

    store.startEditingLastUserMessage();
    store.updateEditingDraft('Edited question');
    store.confirmEditingLastUserMessage();
    store.appendChatReply({
      ...chatReplyFixture,
      event_id: 'assistant-event-2',
      conversation_id: conversationId,
      reply_to_event_id: 'edit-event-1',
      content: 'Edited answer',
    });

    const state = useAiCenterStore.getState();
    expect(state.chatMessages).toHaveLength(2);
    expect(state.chatMessages[0]).toMatchObject({
      event_id: 'user-event-1',
      content: 'Edited question',
      status: 'replied',
    });
    expect(state.chatMessages[1]).toMatchObject({
      event_id: 'assistant-event-2',
      content: 'Edited answer',
      provider: 'gemini',
    });
    expect(state.editingDraft).toBe('');
    expect(state.editingSubmitEventId).toBeNull();
    expect(state.pendingChatEventIds['edit-event-1']).toBe(false);
  });

  it('appendChatError restores editing state and preserves the draft after edit submit failure', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send
      .mockReturnValueOnce('user-event-1')
      .mockReturnValueOnce('edit-event-1');

    store.sendTextMessage('Original question');
    const conversationId = useAiCenterStore.getState().conversationId;
    store.appendChatReply({
      ...chatReplyFixture,
      event_id: 'assistant-event-1',
      conversation_id: conversationId,
      reply_to_event_id: 'user-event-1',
      content: 'Original answer',
    });

    store.startEditingLastUserMessage();
    store.updateEditingDraft('Edited question');
    store.confirmEditingLastUserMessage();
    store.appendChatError({
      ...chatErrorFixture,
      reply_to_event_id: 'edit-event-1',
      error_message: 'Edit failed',
    });

    const state = useAiCenterStore.getState();
    expect(state.chatMessages).toHaveLength(2);
    expect(state.chatMessages[0].content).toBe('Original question');
    expect(state.chatMessages[1].content).toBe('Original answer');
    expect(state.chatMessages[0].status).toBe('replied');
    expect(state.chatMessages[1].status).toBe('sent');
    expect(state.editingMessageEventId).toBe('user-event-1');
    expect(state.editingDraft).toBe('Edited question');
    expect(state.editingSubmitEventId).toBeNull();
    expect(state.editingSubmitError).toBe('Edit failed');
    expect(state.pendingChatEventIds['edit-event-1']).toBe(false);
  });

  it('appendChatError marks target message error and preserves edge-case stability', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce('user-event-1');
    store.sendTextMessage('Will fail');

    store.appendChatError({
      ...chatErrorFixture,
      reply_to_event_id: 'user-event-1',
    });

    let state = useAiCenterStore.getState();
    const failedMessage = state.chatMessages.find((message) => message.event_id === 'user-event-1');
    expect(failedMessage?.status).toBe('error');
    expect(state.pendingChatEventIds['user-event-1']).toBe(false);
    expect(state.chatErrorByEventId['user-event-1']).toBe('Assistant response timed out');

    store.appendChatError(chatGlobalErrorFixture);
    state = useAiCenterStore.getState();
    expect(state.latestChatError?.event_id).toBe('chat-error-2');

    store.appendChatError({
      ...chatErrorFixture,
      event_id: 'chat-error-missing',
      reply_to_event_id: 'missing-user-event',
    });
    expect(useAiCenterStore.getState().latestChatError?.event_id).toBe('chat-error-2');
  });

  it('marks pending text chat as failed when the local request timeout expires', () => {
    vi.useFakeTimers();

    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce('user-event-timeout');

    expect(store.sendTextMessage('Timeout me')).toBe(true);

    vi.advanceTimersByTime(CHAT_CONFIG.CHAT_REQUEST_TIMEOUT_MS);

    const state = useAiCenterStore.getState();
    expect(state.pendingChatEventIds['user-event-timeout']).toBe(false);
    expect(state.chatErrorByEventId['user-event-timeout']).toBe('AI 响应超时，请检查后端服务或网络连接。');
    expect(state.latestChatError?.error_code).toBe('CHAT_REQUEST_TIMEOUT');
    expect(state.chatMessages[0]).toMatchObject({
      event_id: 'user-event-timeout',
      status: 'error',
      error_message: 'AI 响应超时，请检查后端服务或网络连接。',
    });
  });

  it('clears the local request timeout after a successful chat reply', () => {
    vi.useFakeTimers();

    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce('user-event-ok');

    expect(store.sendTextMessage('Need reply')).toBe(true);

    const conversationId = useAiCenterStore.getState().conversationId;
    store.appendChatReply({
      ...chatReplyFixture,
      event_id: 'assistant-event-ok',
      conversation_id: conversationId,
      reply_to_event_id: 'user-event-ok',
    });

    vi.advanceTimersByTime(CHAT_CONFIG.CHAT_REQUEST_TIMEOUT_MS);

    const state = useAiCenterStore.getState();
    expect(state.pendingChatEventIds['user-event-ok']).toBe(false);
    expect(state.chatMessages.find((message) => message.event_id === 'user-event-ok')?.status).toBe('replied');
    expect(state.latestChatError).toBeNull();
  });

  it('fails pending preview speech when the websocket disconnects', () => {
    const store = useAiCenterStore.getState();
    chatWsServiceMock.send.mockReturnValueOnce('voice-event-preview');

    expect(store.sendSpeechMessage({
      audioData: 'base64-audio',
      audioFormat: 'pcm16',
      mode: 'preview',
    })).toBe(true);

    chatSubscribers.onConnectionStateChange?.('disconnected');

    const state = useAiCenterStore.getState();
    expect(state.chatConnectionState).toBe('disconnected');
    expect(state.pendingChatEventIds['voice-event-preview']).toBe(false);
    expect(state.chatMessages).toHaveLength(0);
    expect(state.latestChatError?.error_code).toBe('CHAT_CHANNEL_UNAVAILABLE');
    expect(state.voiceCaptureState).toBe('error');
    expect(state.voiceCaptureError).toBe('聊天连接已断开，请检查后端服务或网络连接。');
  });

  it('supports voice capture state transitions and mismatch guard', () => {
    const store = useAiCenterStore.getState();

    store.setVoiceCaptureRecording();
    expect(useAiCenterStore.getState().voiceCaptureState).toBe('recording');

    store.setVoiceCaptureTranscribing('voice-event-1', 'preview');
    expect(useAiCenterStore.getState().voiceCaptureState).toBe('transcribing');

    store.setVoiceCaptureSent('another-event');
    expect(useAiCenterStore.getState().voiceCaptureState).toBe('transcribing');

    store.setVoiceCaptureSent('voice-event-1');
    expect(useAiCenterStore.getState().voiceCaptureState).toBe('sent');

    store.setVoiceCaptureError('microphone error', 'voice-event-1');
    expect(useAiCenterStore.getState().voiceCaptureState).toBe('error');
    expect(useAiCenterStore.getState().voiceCaptureError).toBe('microphone error');

    store.resetVoiceCapture();
    expect(useAiCenterStore.getState().voiceCaptureState).toBe('idle');
    expect(useAiCenterStore.getState().voiceCaptureError).toBeNull();
  });

  it('reset restores complete initial state', () => {
    const store = useAiCenterStore.getState();

    store.setSpeechEnabled(true);
    store.setSpeechUnlocked(true);
    store.setSpeechSupported(true);
    store.setChatInput('draft input');
    store.startEditingLastUserMessage();
    store.markMessageSpoken('msg-key', 'spoken text');
    store.setVoiceCaptureRecording();

    chatWsServiceMock.send.mockReturnValueOnce('user-event-reset');
    store.sendTextMessage('message before reset');

    store.reset();

    const state = useAiCenterStore.getState();
    expect(state.chatMessages).toHaveLength(0);
    expect(state.chatInput).toBe('');
    expect(state.editingMessageEventId).toBeNull();
    expect(state.editingDraft).toBe('');
    expect(state.editingBaselineUserContent).toBeNull();
    expect(state.editingSubmitEventId).toBeNull();
    expect(state.editingSubmitError).toBeNull();
    expect(state.pendingChatEventIds).toEqual({});
    expect(state.chatErrorByEventId).toEqual({});
    expect(state.latestChatError).toBeNull();
    expect(state.speechEnabled).toBe(false);
    expect(state.speechUnlocked).toBe(false);
    expect(state.speechSupported).toBe(false);
    expect(state.spokenMessages).toEqual({});
    expect(state.lastSpokenAt).toEqual({});
    expect(state.voiceCaptureState).toBe('idle');
    expect(state.voiceCaptureError).toBeNull();
    expect(state.activeVoiceEventId).toBeNull();
    expect(state.activeVoiceMode).toBeNull();
  });

  it('processes websocket subscription callbacks registered at initialization', () => {
    expect(typeof chatSubscribers.onChatReply).toBe('function');
    expect(typeof chatSubscribers.onSpeechTranscript).toBe('function');
    expect(typeof chatSubscribers.onError).toBe('function');

    chatWsServiceMock.send.mockReturnValueOnce('voice-event-subscription');
    useAiCenterStore.getState().sendSpeechMessage({
      audioData: 'base64-audio',
      audioFormat: 'pcm16',
      mode: 'direct',
    });

    const conversationId = useAiCenterStore.getState().conversationId;

    chatSubscribers.onSpeechTranscript?.({
      ...speechTranscriptFixture,
      conversation_id: conversationId,
      reply_to_event_id: 'voice-event-subscription',
      transcript: 'subscription transcript',
    });

    expect(useAiCenterStore.getState().voiceCaptureState).toBe('sent');
  });

  it('updates chatConnectionState when connection status changes', () => {
    expect(typeof chatSubscribers.onConnectionStateChange).toBe('function');

    chatSubscribers.onConnectionStateChange?.('connected');
    expect(useAiCenterStore.getState().chatConnectionState).toBe('connected');

    chatSubscribers.onConnectionStateChange?.('reconnecting');
    expect(useAiCenterStore.getState().chatConnectionState).toBe('reconnecting');
  });
});
