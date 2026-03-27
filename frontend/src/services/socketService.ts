/**
 * WebSocket Service
 * Handles connection, heartbeat, and reconnection with exponential backoff.
 */
import type {
  ChatInputType,
  ChatRequestEnvelope,
  ChatReplyPayload,
  ChatErrorPayload,
  RiskObject,
  Target,
  WebSocketMessage,
} from '../types/schema';
import { WS_CONFIG } from '../config/constants';
import { useRiskStore } from '../store/useRiskStore';
import { useAiCenterStore } from '../store/useAiCenterStore';

type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';

interface SendChatMessageParams {
  sequenceId: string;
  messageId: string;
  inputType?: ChatInputType;
  content: string;
}

class SocketService {
  private socket: WebSocket | null = null;
  private state: ConnectionState = 'disconnected';
  private reconnectAttempts = 0;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private heartbeatTimeoutTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private lastSequenceId = 0;
  private inFlightChatMessageIds = new Set<string>();

  connect(url: string = WS_CONFIG.URL): void {
    if (this.state === 'connected' || this.state === 'connecting') {
      console.warn('[WS] Already connected or connecting');
      return;
    }

    this.state = 'connecting';
    console.log('[WS] Connecting to', url);

    try {
      this.socket = new WebSocket(url);
      this.setupEventHandlers();
    } catch (error) {
      console.error('[WS] Connection error:', error);
      this.handleReconnect();
    }
  }

  disconnect(): void {
    this.state = 'disconnected';
    this.clearTimers();
    this.failAllPendingChats('CLIENT_DISCONNECTED', '连接已断开，请重新发送');

    if (this.socket) {
      this.socket.close(1000, 'Client disconnect');
      this.socket = null;
    }

    useRiskStore.getState().setConnectionStatus(false);
    console.log('[WS] Disconnected');
  }

  send(message: WebSocketMessage): boolean {
    if (!this.socket || this.state !== 'connected') {
      console.warn('[WS] Cannot send - not connected');
      return false;
    }

    this.socket.send(JSON.stringify(message));
    return true;
  }

  sendChatMessage({ sequenceId, messageId, inputType = 'TEXT', content }: SendChatMessageParams): boolean {
    const text = content.trim();
    if (!text) {
      useAiCenterStore.getState().markChatMessageError(messageId, 'EMPTY_MESSAGE', '消息内容不能为空');
      return false;
    }

    if (this.inFlightChatMessageIds.has(messageId)) {
      useAiCenterStore.getState().markChatMessageError(messageId, 'DUPLICATE_MESSAGE', '该消息正在发送，请勿重复提交');
      return false;
    }

    if (!this.socket || this.state !== 'connected') {
      useAiCenterStore.getState().markChatMessageError(messageId, 'SOCKET_NOT_CONNECTED', '当前连接不可用，请稍后重试');
      return false;
    }

    const message: ChatRequestEnvelope = {
      type: 'CHAT',
      message: {
        role: 'user',
        sequence_id: sequenceId,
        message_id: messageId,
        input_type: inputType,
        content: text,
      },
    };

    this.inFlightChatMessageIds.add(messageId);
    const didSend = this.send(message);
    if (!didSend) {
      this.inFlightChatMessageIds.delete(messageId);
      useAiCenterStore.getState().markChatMessageError(messageId, 'SOCKET_NOT_CONNECTED', '当前连接不可用，请稍后重试');
      return false;
    }

    return true;
  }

  getState(): ConnectionState {
    return this.state;
  }

  private setupEventHandlers(): void {
    if (!this.socket) {
      return;
    }

    this.socket.onopen = () => {
      console.log('[WS] Connected');
      this.state = 'connected';
      this.reconnectAttempts = 0;
      useRiskStore.getState().setConnectionStatus(true);
      this.startHeartbeat();
    };

    this.socket.onclose = (event) => {
      console.log('[WS] Closed:', event.code, event.reason);
      this.clearTimers();

      if (this.state !== 'disconnected') {
        useRiskStore.getState().setConnectionStatus(false, 'Connection lost');
        this.failAllPendingChats('SOCKET_CLOSED', '连接已中断，请重试');
        this.handleReconnect();
      }
    };

    this.socket.onerror = (error) => {
      console.error('[WS] Error:', error);
      useRiskStore.getState().setConnectionStatus(false, 'Connection error');
    };

    this.socket.onmessage = (event) => {
      this.handleMessage(event.data);
    };
  }

  private handleMessage(data: string): void {
    try {
      const message: WebSocketMessage = JSON.parse(data);

      if (message.type === 'PONG') {
        this.clearHeartbeatTimeout();
        return;
      }

      switch (message.type) {
        case 'RISK_UPDATE':
        case 'SNAPSHOT':
          if (!this.isRiskMessageInOrder(message)) {
            return;
          }
          if (isRiskObjectPayload(message.payload)) {
            this.processRiskObject(message.payload);
          }
          return;
        case 'CHAT_REPLY':
          if (isChatReplyPayload(message.payload)) {
            this.processChatReply(message.payload);
          }
          return;
        case 'CHAT_ERROR':
          if (isChatErrorPayload(message.payload)) {
            this.processChatError(message.payload);
          }
          return;
        case 'ALERT':
          console.log('[WS] Alert received:', message.payload);
          return;
        default:
          return;
      }
    } catch (error) {
      console.error('[WS] Message parse error:', error);
    }
  }

  private isRiskMessageInOrder(message: WebSocketMessage): boolean {
    if (typeof message.sequence_id !== 'number') {
      return true;
    }

    if (message.sequence_id <= this.lastSequenceId) {
      console.warn('[WS] Out of order message:', message.sequence_id);
      return false;
    }

    this.lastSequenceId = message.sequence_id;
    return true;
  }

  private processRiskObject(data: RiskObject): void {
    if (!data.risk_object_id || !data.timestamp || !data.own_ship) {
      console.warn('[WS] Invalid RiskObject received');
      return;
    }

    useRiskStore.getState().setRiskObject(data);
    useAiCenterStore.getState().ingestRiskObjectForAi(data, mergeTargets(data.targets, data.all_targets));
  }

  private processChatReply(payload: ChatReplyPayload): void {
    if (payload.reply_to_message_id) {
      this.inFlightChatMessageIds.delete(payload.reply_to_message_id);
    }

    if (payload.sequence_id !== useAiCenterStore.getState().chatSessionId) {
      return;
    }

    useAiCenterStore.getState().appendChatReply(payload);
  }

  private processChatError(payload: ChatErrorPayload): void {
    if (payload.reply_to_message_id) {
      this.inFlightChatMessageIds.delete(payload.reply_to_message_id);
    }

    if (payload.sequence_id !== useAiCenterStore.getState().chatSessionId) {
      return;
    }

    useAiCenterStore.getState().appendChatError(payload);
  }

  private failAllPendingChats(errorCode: string, errorMessage: string): void {
    const store = useAiCenterStore.getState();
    this.inFlightChatMessageIds.forEach((messageId) => {
      store.markChatMessageError(messageId, errorCode, errorMessage);
    });
    this.inFlightChatMessageIds.clear();
  }

  private handleReconnect(): void {
    if (this.state === 'disconnected') {
      return;
    }

    this.state = 'reconnecting';
    this.reconnectAttempts += 1;

    const delay = Math.min(
      WS_CONFIG.RECONNECT_BASE_DELAY_MS * Math.pow(WS_CONFIG.RECONNECT_MULTIPLIER, this.reconnectAttempts - 1),
      WS_CONFIG.RECONNECT_MAX_DELAY_MS,
    );

    console.log(`[WS] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);

    this.reconnectTimer = setTimeout(() => {
      this.connect();
    }, delay);
  }

  private startHeartbeat(): void {
    this.heartbeatTimer = setInterval(() => {
      this.send({ type: 'PING' });
      this.startHeartbeatTimeout();
    }, WS_CONFIG.HEARTBEAT_INTERVAL_MS);
  }

  private startHeartbeatTimeout(): void {
    this.clearHeartbeatTimeout();
    this.heartbeatTimeoutTimer = setTimeout(() => {
      console.warn('[WS] Heartbeat timeout');
      this.socket?.close(4000, 'Heartbeat timeout');
    }, WS_CONFIG.HEARTBEAT_TIMEOUT_MS);
  }

  private clearHeartbeatTimeout(): void {
    if (this.heartbeatTimeoutTimer) {
      clearTimeout(this.heartbeatTimeoutTimer);
      this.heartbeatTimeoutTimer = null;
    }
  }

  private clearTimers(): void {
    this.clearHeartbeatTimeout();
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}

function mergeTargets(targets: Target[], allTargets?: Target[]): Target[] {
  const targetMap = new Map<string, Target>();

  [...targets, ...(allTargets || [])].forEach((target) => {
    targetMap.set(target.id, target);
  });

  return [...targetMap.values()];
}

function isRiskObjectPayload(payload: WebSocketMessage['payload']): payload is RiskObject {
  return Boolean(payload && typeof payload === 'object' && 'risk_object_id' in payload && 'own_ship' in payload);
}

function isChatReplyPayload(payload: WebSocketMessage['payload']): payload is ChatReplyPayload {
  return Boolean(payload && typeof payload === 'object' && 'reply_to_message_id' in payload && 'content' in payload);
}

function isChatErrorPayload(payload: WebSocketMessage['payload']): payload is ChatErrorPayload {
  return Boolean(payload && typeof payload === 'object' && 'error_code' in payload && 'error_message' in payload);
}

export const socketService = new SocketService();
