import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WS_CONFIG } from '../config/constants';
import {
  chatErrorFixture,
  chatReplyFixture,
  clearHistoryAckFixture,
  speechTranscriptFixture,
} from '../test/fixtures';
import { chatWsService } from './chatWsService';

class MockWebSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;

  static instances: MockWebSocket[] = [];

  static reset(): void {
    MockWebSocket.instances = [];
  }

  url: string;
  readyState = MockWebSocket.CONNECTING;
  sentMessages: string[] = [];
  closeArgs: { code?: number; reason?: string } | null = null;

  onopen: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  send(data: string): void {
    this.sentMessages.push(data);
  }

  close(code?: number, reason?: string): void {
    this.closeArgs = { code, reason };
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.(new CloseEvent('close', {
      code: code ?? 1000,
      reason: reason ?? '',
    }));
  }

  triggerOpen(): void {
    this.readyState = MockWebSocket.OPEN;
    this.onopen?.(new Event('open'));
  }

  triggerMessage(data: string): void {
    this.onmessage?.(new MessageEvent('message', { data }));
  }

  triggerClose(): void {
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.(new CloseEvent('close', {
      code: 1006,
      reason: 'Abnormal closure',
    }));
  }
}

describe('chatWsService', () => {
  beforeEach(() => {
    Object.defineProperty(globalThis, 'WebSocket', {
      value: MockWebSocket,
      writable: true,
      configurable: true,
    });
    chatWsService.disconnect();
    MockWebSocket.reset();
  });

  afterEach(() => {
    chatWsService.disconnect();
    MockWebSocket.reset();
  });

  it('connect transitions to connected on websocket open', () => {
    chatWsService.connect('ws://localhost:8080/api/v2/chat');
    expect(chatWsService.getState()).toBe('connecting');

    const socket = MockWebSocket.instances[0];
    socket.triggerOpen();

    expect(chatWsService.getState()).toBe('connected');
  });

  it('send generates event ids and sends chat uplink envelopes', () => {
    chatWsService.connect('ws://localhost:8080/api/v2/chat');
    const socket = MockWebSocket.instances[0];
    socket.triggerOpen();

    const randomSpy = vi.spyOn(globalThis.crypto, 'randomUUID');
    randomSpy
      .mockReturnValueOnce('11111111-1111-1111-1111-111111111111')
      .mockReturnValueOnce('22222222-2222-2222-2222-222222222222')
      .mockReturnValueOnce('33333333-3333-3333-3333-333333333333');

    const chatEventId = chatWsService.send('CHAT', {
      conversation_id: 'conversation-1',
      content: 'hello',
      selected_target_ids: ['TGT-1'],
      edit_last_user_message: true,
    });

    const speechEventId = chatWsService.send('SPEECH', {
      conversation_id: 'conversation-1',
      audio_data: 'base64-data',
      audio_format: 'pcm16',
      mode: 'direct',
    });

    const clearHistoryEventId = chatWsService.send('CLEAR_HISTORY', {
      conversation_id: 'conversation-1',
    });

    expect(chatEventId).toBe('11111111-1111-1111-1111-111111111111');
    expect(speechEventId).toBe('22222222-2222-2222-2222-222222222222');
    expect(clearHistoryEventId).toBe('33333333-3333-3333-3333-333333333333');

    const [chatEnvelope, speechEnvelope, clearHistoryEnvelope] = socket.sentMessages.map((message) => JSON.parse(message));

    expect(chatEnvelope).toEqual({
      type: 'CHAT',
      source: 'client',
      payload: {
        conversation_id: 'conversation-1',
        content: 'hello',
        selected_target_ids: ['TGT-1'],
        edit_last_user_message: true,
        event_id: '11111111-1111-1111-1111-111111111111',
      },
    });

    expect(speechEnvelope).toEqual({
      type: 'SPEECH',
      source: 'client',
      payload: {
        conversation_id: 'conversation-1',
        audio_data: 'base64-data',
        audio_format: 'pcm16',
        mode: 'direct',
        event_id: '22222222-2222-2222-2222-222222222222',
      },
    });

    expect(clearHistoryEnvelope).toEqual({
      type: 'CLEAR_HISTORY',
      source: 'client',
      payload: {
        conversation_id: 'conversation-1',
        event_id: '33333333-3333-3333-3333-333333333333',
      },
    });
  });

  it('send returns null or false while disconnected', () => {
    expect(chatWsService.send('CHAT', {
      conversation_id: 'conversation-1',
      content: 'hello',
    })).toBeNull();

    expect(chatWsService.send('SPEECH', {
      conversation_id: 'conversation-1',
      audio_data: 'base64-data',
      audio_format: 'pcm16',
      mode: 'preview',
    })).toBeNull();

    expect(chatWsService.send('PING', null)).toBe(false);
  });

  it('handleMessage dispatches CHAT_REPLY, SPEECH_TRANSCRIPT, ERROR, CLEAR_HISTORY_ACK and PONG', () => {
    const chatReplySpy = vi.fn();
    const transcriptSpy = vi.fn();
    const errorSpy = vi.fn();
    const clearHistoryAckSpy = vi.fn();
    const pongSpy = vi.fn();

    const offReply = chatWsService.onChatReply(chatReplySpy);
    const offTranscript = chatWsService.onSpeechTranscript(transcriptSpy);
    const offError = chatWsService.onError(errorSpy);
    const offClear = chatWsService.onClearHistoryAck(clearHistoryAckSpy);
    const offPong = chatWsService.onPong(pongSpy);

    chatWsService.connect('ws://localhost:8080/api/v2/chat');
    const socket = MockWebSocket.instances[0];
    socket.triggerOpen();

    socket.triggerMessage(JSON.stringify({
      type: 'CHAT_REPLY',
      source: 'server',
      sequence_id: 'seq-1',
      payload: chatReplyFixture,
    }));

    socket.triggerMessage(JSON.stringify({
      type: 'SPEECH_TRANSCRIPT',
      source: 'server',
      sequence_id: 'seq-2',
      payload: speechTranscriptFixture,
    }));

    socket.triggerMessage(JSON.stringify({
      type: 'ERROR',
      source: 'server',
      sequence_id: 'seq-3',
      payload: chatErrorFixture,
    }));

    socket.triggerMessage(JSON.stringify({
      type: 'CLEAR_HISTORY_ACK',
      source: 'server',
      sequence_id: 'seq-4',
      payload: clearHistoryAckFixture,
    }));

    socket.triggerMessage(JSON.stringify({
      type: 'PONG',
      source: 'server',
      sequence_id: 'seq-5',
      payload: null,
    }));

    expect(chatReplySpy).toHaveBeenCalledWith(chatReplyFixture);
    expect(transcriptSpy).toHaveBeenCalledWith(speechTranscriptFixture);
    expect(errorSpy).toHaveBeenCalledWith(chatErrorFixture);
    expect(clearHistoryAckSpy).toHaveBeenCalledWith(clearHistoryAckFixture);
    expect(pongSpy).toHaveBeenCalledTimes(1);

    offReply();
    offTranscript();
    offError();
    offClear();
    offPong();
  });

  it('ignores invalid JSON downlink messages', () => {
    const chatReplySpy = vi.fn();
    const offReply = chatWsService.onChatReply(chatReplySpy);

    chatWsService.connect('ws://localhost:8080/api/v2/chat');
    const socket = MockWebSocket.instances[0];
    socket.triggerOpen();

    socket.triggerMessage('not-json');

    expect(chatReplySpy).not.toHaveBeenCalled();
    offReply();
  });

  it('disconnect stops heartbeat, closes socket and disables auto reconnect', () => {
    vi.useFakeTimers();

    chatWsService.connect('ws://localhost:8080/api/v2/chat');
    const socket = MockWebSocket.instances[0];
    socket.triggerOpen();

    vi.advanceTimersByTime(WS_CONFIG.HEARTBEAT_INTERVAL_MS);
    expect(socket.sentMessages).toHaveLength(1);
    expect(JSON.parse(socket.sentMessages[0]).type).toBe('PING');

    chatWsService.disconnect();

    expect(socket.closeArgs).toEqual({ code: 1000, reason: 'Client disconnect' });
    expect(chatWsService.getState()).toBe('disconnected');

    const socketCount = MockWebSocket.instances.length;
    vi.advanceTimersByTime(WS_CONFIG.RECONNECT_MAX_DELAY_MS * 2);
    expect(MockWebSocket.instances).toHaveLength(socketCount);
  });

  it('schedules reconnect with backoff when socket closes unexpectedly', () => {
    vi.useFakeTimers();

    chatWsService.connect('ws://localhost:8080/api/v2/chat');
    const firstSocket = MockWebSocket.instances[0];
    firstSocket.triggerOpen();

    firstSocket.triggerClose();
    expect(chatWsService.getState()).toBe('reconnecting');

    vi.advanceTimersByTime(WS_CONFIG.RECONNECT_BASE_DELAY_MS - 1);
    expect(MockWebSocket.instances).toHaveLength(1);

    vi.advanceTimersByTime(1);
    expect(MockWebSocket.instances).toHaveLength(2);

    const secondSocket = MockWebSocket.instances[1];
    secondSocket.triggerOpen();
    expect(chatWsService.getState()).toBe('connected');
  });

  it('notifies connection state changes to subscribers', () => {
    const stateSpy = vi.fn();
    chatWsService.onConnectionStateChange(stateSpy);

    chatWsService.connect('ws://localhost:8080/api/v2/chat');
    expect(stateSpy).toHaveBeenLastCalledWith('reconnecting');

    const socket = MockWebSocket.instances[0];
    socket.triggerOpen();
    expect(stateSpy).toHaveBeenLastCalledWith('connected');

    socket.triggerClose();
    expect(stateSpy).toHaveBeenLastCalledWith('reconnecting');

    chatWsService.disconnect();
    expect(stateSpy).toHaveBeenLastCalledWith('disconnected');
  });
});
