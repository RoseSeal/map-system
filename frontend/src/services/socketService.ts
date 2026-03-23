/**
 * WebSocket Service
 * Handles connection, heartbeat, and reconnection with exponential backoff
 */
// 负责管理 WebSocket 生命周期：发起连接 -> 注册事件 -> 维护心跳 -> 接收消息 -> 更新 store -> 异常断开后按指数退避重连
// 目前这个类不是纯管理连接，而是做了初步的消息检验和分流，后续可以将 handleMessage 和 processRiskObject 拆分出去
import type { WebSocketMessage, RiskObject } from '../types/schema';
import { WS_CONFIG } from '../config/constants';
import { useRiskStore } from '../store/useRiskStore';

type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting'; // 类似于java的轻量版枚举

// WebSocket 生命周期管理器：负责连接、心跳保活、消息接收、状态同步和断线重连
class SocketService {
  private socket: WebSocket | null = null; // 当前WebSocket实例，默认为null
  private state: ConnectionState = 'disconnected'; // 连接状态，初始为disconnected
  private reconnectAttempts = 0; // 重连尝试次数
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null; // 心跳定时器
  private heartbeatTimeoutTimer: ReturnType<typeof setTimeout> | null = null; // 心跳超时定时器
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null; // 重连定时器
  private lastSequenceId = 0;
  
  /**
   * Connect to WebSocket server
   * 默认参数为WS_CONFIG.URL，允许外部覆盖URL
   * 发起连接请求并注册事件；真正的连接结果通过 onopen/onerror/onclose 回调体现
   */
  connect(url: string = WS_CONFIG.URL): void {
    // 防止重复连接
    if (this.state === 'connected' || this.state === 'connecting') {
      console.warn('[WS] Already connected or connecting');
      return;
    }
    
    // 修改状态为连接中
    this.state = 'connecting';
    console.log('[WS] Connecting to', url);
    
    // 尝试创建 WebSocket 实例并注册事件；真正的连接成功/失败主要通过后续事件回调体现
    try {
      // 新建连接
      this.socket = new WebSocket(url);
      // 设置事件处理器
      this.setupEventHandlers();
    } catch (error) {
      console.error('[WS] Connection error:', error);
      this.handleReconnect();
    }
  }
  
  /**
   * Disconnect from WebSocket server
   * 主动断开：先切到 disconnected，避免 close 事件再次触发自动重连
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
    
    this.socket.send(JSON.stringify(message)); // 调用官方接口，将JavaScript对象转换为JSON字符串发送
  }
  
  /**
   * Get current connection state
   */
  getState(): ConnectionState {
    return this.state;
  }
  
  // 给websocket设置事件处理器，挂载onopen、onclose、onerror、onmessage事件
  private setupEventHandlers(): void {
    if (!this.socket) return;
    
    // 连接后做什么
    this.socket.onopen = () => {
      // 连接成功后重置状态和重连计数器，更新全局状态（更新UI），并启动心跳
      console.log('[WS] Connected');
      this.state = 'connected';
      this.reconnectAttempts = 0;
      useRiskStore.getState().setConnectionStatus(true);
      this.startHeartbeat();
    };
    

    // 连接关闭后做什么
    this.socket.onclose = (event) => {
      // 连接关闭后清理定时器，更新全局状态（更新UI），并尝试重连（如果不是主动断开）
      console.log('[WS] Closed:', event.code, event.reason);
      this.clearTimers();
      
      if (this.state !== 'disconnected') {
        useRiskStore.getState().setConnectionStatus(false, 'Connection lost');
        this.handleReconnect();
      }
    };
    
    // 连接错误后做什么
    this.socket.onerror = (error) => {
      // 连接错误时先记录错误并更新 UI；当前实现把真正的重连触发放在 onclose 中
      console.error('[WS] Error:', error);
      useRiskStore.getState().setConnectionStatus(false, 'Connection error');
    };
    
    // 收到消息后做什么
    this.socket.onmessage = (event) => {
      // 处理消息
      this.handleMessage(event.data);
    };
  }
  
  // 处理收到的消息，解析JSON并根据消息类型更新全局状态
  private handleMessage(data: string): void {
    try {
      // 将收到的字符串数据解析为WebSocketMessage对象
      const message: WebSocketMessage = JSON.parse(data); // 使用官方接口，输入JSON字符串，输出JavaScript对象
      // 按消息类型分类处理
      // Handle heartbeat response
      if (message.type === 'PONG') {
        this.clearHeartbeatTimeout();
        return;
      }
      
      // Check sequence ID for ordering
      if (message.sequence_id !== undefined) {
        // 当前只做单调递增校验：旧消息或重复消息直接丢弃，不做乱序重排
        if (message.sequence_id <= this.lastSequenceId) {
          console.warn('[WS] Out of order message:', message.sequence_id);
          return;
        }
        this.lastSequenceId = message.sequence_id;
      }
      
      // Handle payload
      // 若消息是风险更新或者全量快照，就调用processRiskObject方法处理风险对象数据
      // 当前 SNAPSHOT 和 RISK_UPDATE 共用同一处理逻辑，暂未区分“全量快照”和“增量更新”
      // 若是警报消息，则打印日志（未来可扩展为触发UI事件）
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
  
  // 处理风险对象数据，验证必要字段后更新全局状态(类似后端的DTO校验和Service层处理)
  // 仅做最小必要字段校验，校验通过后写入 store
  private processRiskObject(data: RiskObject): void {
    // Validate essential fields
    if (!data.risk_object_id || !data.timestamp || !data.own_ship) {
      console.warn('[WS] Invalid RiskObject received');
      return;
    }
    // 将数据喂给store，需要更新的组件应当订阅这个store的变化以触发UI更新
    useRiskStore.getState().setRiskObject(data);
  }
  
  
  // 处理重连逻辑，使用指数退避算法计算重连延迟，并设置定时器尝试重连
  private handleReconnect(): void {
    if (this.state === 'disconnected') return;
    
    this.state = 'reconnecting';
    this.reconnectAttempts++;
    
    // Exponential backoff
    // 退避指数算法：重连延迟 = 基础延迟 * (乘数 ^ (重连尝试次数 - 1))，但不超过最大延迟
    const delay = Math.min(
      WS_CONFIG.RECONNECT_BASE_DELAY_MS * Math.pow(WS_CONFIG.RECONNECT_MULTIPLIER, this.reconnectAttempts - 1),
      WS_CONFIG.RECONNECT_MAX_DELAY_MS
    );
    
    console.log(`[WS] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);
    
    this.reconnectTimer = setTimeout(() => {
      this.connect();
    }, delay);
  }

  /*
  * Heartbeat logic: 连接成功后启动心跳定时器，每隔一定时间发送PING消息，并启动心跳超时定时器等待PONG响应
  */
  private startHeartbeat(): void {
    this.heartbeatTimer = setInterval(() => {
      this.send({ type: 'PING' });
      this.startHeartbeatTimeout();
    }, WS_CONFIG.HEARTBEAT_INTERVAL_MS);// 每隔一定时间发送PING消息，心跳发送间隔：30000ms，定义在src/config/constants.ts中
  }
  
  private startHeartbeatTimeout(): void {
    this.clearHeartbeatTimeout();
    this.heartbeatTimeoutTimer = setTimeout(() => {
      console.warn('[WS] Heartbeat timeout');
      this.socket?.close(4000, 'Heartbeat timeout');
    }, WS_CONFIG.HEARTBEAT_TIMEOUT_MS);// 启动心跳超时定时器，等待PONG响应，心跳超时时间：10000ms，定义在src/config/constants.ts中
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

// Singleton instance
// 导出一个单例实例，整个应用共享这个WebSocket连接服务
export const socketService = new SocketService();
