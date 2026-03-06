/**
 * WebSocket Service
 * Handles connection, heartbeat, and reconnection with exponential backoff
 */

import type { WebSocketMessage, RiskObject } from '../types/schema';
import { WS_CONFIG } from '../config/constants';
import { useRiskStore } from '../store/useRiskStore';

type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';

class SocketService {
  private socket: WebSocket | null = null;
  private state: ConnectionState = 'disconnected';
  private reconnectAttempts = 0;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private heartbeatTimeoutTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private lastSequenceId = 0;
  
  /**
   * Connect to WebSocket server
   */
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
  
  /**
   * Disconnect from WebSocket server
   */
  disconnect(): void {
    this.state = 'disconnected';
    this.clearTimers();
    
    if (this.socket) {
      this.socket.close(1000, 'Client disconnect');
      this.socket = null;
    }
    
    useRiskStore.getState().setConnectionStatus(false);
    console.log('[WS] Disconnected');
  }
  
  /**
   * Send a message
   */
  send(message: WebSocketMessage): void {
    if (!this.socket || this.state !== 'connected') {
      console.warn('[WS] Cannot send - not connected');
      return;
    }
    
    this.socket.send(JSON.stringify(message));
  }
  
  /**
   * Get current connection state
   */
  getState(): ConnectionState {
    return this.state;
  }
  
  private setupEventHandlers(): void {
    if (!this.socket) return;
    
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
      
      // Handle heartbeat response
      if (message.type === 'PONG') {
        this.clearHeartbeatTimeout();
        return;
      }
      
      // Check sequence ID for ordering
      if (message.sequence_id !== undefined) {
        if (message.sequence_id <= this.lastSequenceId) {
          console.warn('[WS] Out of order message:', message.sequence_id);
          return;
        }
        this.lastSequenceId = message.sequence_id;
      }
      
      // Handle payload
      if (message.type === 'RISK_UPDATE' || message.type === 'SNAPSHOT') {
        if (message.payload) {
          this.processRiskObject(message.payload);
        }
      } else if (message.type === 'ALERT') {
        console.log('[WS] Alert received:', message.payload);
        // Could emit alert events here
      }
    } catch (error) {
      console.error('[WS] Message parse error:', error);
    }
  }
  
  private processRiskObject(data: RiskObject): void {
    // Validate essential fields
    if (!data.risk_object_id || !data.timestamp || !data.own_ship) {
      console.warn('[WS] Invalid RiskObject received');
      return;
    }
    
    useRiskStore.getState().setRiskObject(data);
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
  
  private handleReconnect(): void {
    if (this.state === 'disconnected') return;
    
    this.state = 'reconnecting';
    this.reconnectAttempts++;
    
    // Exponential backoff
    const delay = Math.min(
      WS_CONFIG.RECONNECT_BASE_DELAY_MS * Math.pow(WS_CONFIG.RECONNECT_MULTIPLIER, this.reconnectAttempts - 1),
      WS_CONFIG.RECONNECT_MAX_DELAY_MS
    );
    
    console.log(`[WS] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);
    
    this.reconnectTimer = setTimeout(() => {
      this.connect();
    }, delay);
  }
}

// Singleton instance
export const socketService = new SocketService();
