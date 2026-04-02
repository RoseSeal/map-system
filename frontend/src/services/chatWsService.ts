import type {
  ChatDownlinkEnvelope,
  ChatDownlinkType,
  ChatErrorPayload,
  ChatReplyPayload,
  ChatRequestPayload,
  ChatUplinkEnvelope,
  ChatUplinkType,
  SpeechRequestPayload,
  SpeechTranscriptPayload,
} from '../types/schema';
import { BACKEND_CONFIG } from '../config/constants';

type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';
type ChatReplyCallback = (payload: ChatReplyPayload) => void;
type SpeechTranscriptCallback = (payload: SpeechTranscriptPayload) => void;
type ErrorCallback = (payload: ChatErrorPayload) => void;
type PongCallback = () => void;

const DEFAULT_CHAT_WS_URL: string = BACKEND_CONFIG.CHAT_WS_URL;
const HEARTBEAT_INTERVAL_MS = 30000;
const RECONNECT_BASE_DELAY_MS = 1000;
const RECONNECT_MAX_DELAY_MS = 30000;

class ChatWsService {
  private socket: WebSocket | null = null;
  private state: ConnectionState = 'disconnected';
  private reconnectAttempts = 0;
  private reconnectTimer: number | null = null;
  private heartbeatTimer: number | null = null;
  private currentUrl = DEFAULT_CHAT_WS_URL;
  private manuallyDisconnected = false;

  private chatReplyCallbacks = new Set<ChatReplyCallback>();
  private speechTranscriptCallbacks = new Set<SpeechTranscriptCallback>();
  private errorCallbacks = new Set<ErrorCallback>();
  private pongCallbacks = new Set<PongCallback>();

  connect(url: string = this.currentUrl): void {
    if (this.state === 'connected' || this.state === 'connecting') {
      return;
    }

    this.currentUrl = url;
    this.manuallyDisconnected = false;
    this.clearReconnectTimer();
    this.state = this.reconnectAttempts > 0 ? 'reconnecting' : 'connecting';

    const socket = new WebSocket(url);
    this.socket = socket;

    socket.onopen = () => {
      if (this.socket !== socket) {
        socket.close(1000, 'Superseded connection');
        return;
      }

      this.state = 'connected';
      this.reconnectAttempts = 0;
      this.startHeartbeat();
    };

    socket.onmessage = (event) => {
      if (this.socket !== socket) {
        return;
      }

      this.handleMessage(event.data);
    };

    socket.onerror = (event) => {
      if (this.socket !== socket) {
        return;
      }

      console.error('[chatWsService] WebSocket error', event);
    };

    socket.onclose = () => {
      if (this.socket !== socket) {
        return;
      }

      this.stopHeartbeat();
      this.socket = null;

      if (this.manuallyDisconnected) {
        this.state = 'disconnected';
        return;
      }

      this.scheduleReconnect();
    };
  }

  disconnect(): void {
    this.manuallyDisconnected = true;
    this.state = 'disconnected';
    this.clearReconnectTimer();
    this.stopHeartbeat();

    const socket = this.socket;
    this.socket = null;

    if (!socket) {
      return;
    }

    socket.close(1000, 'Client disconnect');
  }

  send(type: 'PING', payload: null): boolean;
  send(type: 'CHAT', payload: Omit<ChatRequestPayload, 'event_id'>): string | null;
  send(type: 'SPEECH', payload: Omit<SpeechRequestPayload, 'event_id'>): string | null;
  send(
    type: ChatUplinkType,
    payload: Omit<ChatRequestPayload, 'event_id'> | Omit<SpeechRequestPayload, 'event_id'> | null,
  ): boolean | string | null {
    if (!this.socket || this.state !== 'connected') {
      return type === 'PING' ? false : null;
    }

    const { envelope, eventId } = this.buildEnvelope(type, payload);
    this.socket.send(JSON.stringify(envelope));
    return type === 'PING' ? true : eventId;
  }

  onChatReply(cb: ChatReplyCallback): () => void {
    this.chatReplyCallbacks.add(cb);
    return () => {
      this.chatReplyCallbacks.delete(cb);
    };
  }

  onSpeechTranscript(cb: SpeechTranscriptCallback): () => void {
    this.speechTranscriptCallbacks.add(cb);
    return () => {
      this.speechTranscriptCallbacks.delete(cb);
    };
  }

  onError(cb: ErrorCallback): () => void {
    this.errorCallbacks.add(cb);
    return () => {
      this.errorCallbacks.delete(cb);
    };
  }

  onPong(cb: PongCallback): () => void {
    this.pongCallbacks.add(cb);
    return () => {
      this.pongCallbacks.delete(cb);
    };
  }

  getState(): ConnectionState {
    return this.state;
  }

  private buildEnvelope(
    type: ChatUplinkType,
    payload: Omit<ChatRequestPayload, 'event_id'> | Omit<SpeechRequestPayload, 'event_id'> | null,
  ): { envelope: ChatUplinkEnvelope; eventId: string | null } {
    if (type === 'PING') {
      return {
        envelope: {
          type,
          source: 'client',
          payload: null,
        },
        eventId: null,
      };
    }

    const eventId = crypto.randomUUID();
    return {
      envelope: {
        type,
        source: 'client',
        payload: {
          ...payload,
          event_id: eventId,
        } as ChatRequestPayload | SpeechRequestPayload,
      },
      eventId,
    };
  }

  private handleMessage(data: string): void {
    let message: ChatDownlinkEnvelope;

    try {
      message = JSON.parse(data) as ChatDownlinkEnvelope;
    } catch (error) {
      console.error('[chatWsService] Failed to parse message', error);
      return;
    }

    switch (message.type) {
      case 'PONG':
        this.pongCallbacks.forEach((cb) => cb());
        return;
      case 'CHAT_REPLY':
        if (isChatReplyPayload(message.payload)) {
          const payload = message.payload;
          this.chatReplyCallbacks.forEach((cb) => cb(payload));
        }
        return;
      case 'SPEECH_TRANSCRIPT':
        if (isSpeechTranscriptPayload(message.payload)) {
          const payload = message.payload;
          this.speechTranscriptCallbacks.forEach((cb) => cb(payload));
        }
        return;
      case 'ERROR':
        if (isChatErrorPayload(message.payload)) {
          const payload = message.payload;
          this.errorCallbacks.forEach((cb) => cb(payload));
        }
        return;
      default:
        assertNever(message.type);
    }
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = window.setInterval(() => {
      this.send('PING', null);
    }, HEARTBEAT_INTERVAL_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer !== null) {
      window.clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private scheduleReconnect(): void {
    this.state = 'reconnecting';
    this.reconnectAttempts += 1;

    const delay = Math.min(
      RECONNECT_BASE_DELAY_MS * (2 ** (this.reconnectAttempts - 1)),
      RECONNECT_MAX_DELAY_MS,
    );

    this.clearReconnectTimer();
    this.reconnectTimer = window.setTimeout(() => {
      this.connect(this.currentUrl);
    }, delay);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}

function isChatReplyPayload(payload: ChatDownlinkEnvelope['payload']): payload is ChatReplyPayload {
  return Boolean(
    payload
    && typeof payload === 'object'
    && 'conversation_id' in payload
    && 'content' in payload
    && 'reply_to_event_id' in payload,
  );
}

function isSpeechTranscriptPayload(payload: ChatDownlinkEnvelope['payload']): payload is SpeechTranscriptPayload {
  return Boolean(
    payload
    && typeof payload === 'object'
    && 'conversation_id' in payload
    && 'transcript' in payload
    && 'reply_to_event_id' in payload,
  );
}

function isChatErrorPayload(payload: ChatDownlinkEnvelope['payload']): payload is ChatErrorPayload {
  return Boolean(
    payload
    && typeof payload === 'object'
    && 'connection' in payload
    && 'error_code' in payload
    && 'error_message' in payload,
  );
}

function assertNever(value: ChatDownlinkType): never {
  throw new Error(`Unsupported chat downlink type: ${value}`);
}

export const chatWsService = new ChatWsService();
