import { parseWsFrame, WS_PROTOCOL_VERSION, type WsFrame } from 'hchat-shared-types';
import { ChatConnectionError } from './errors';

export type ConnectionState =
  | 'idle'
  | 'connecting'
  | 'authenticating'
  | 'authenticated'
  | 'reconnecting'
  | 'closed';

export interface SocketOptions {
  url: string;
  getToken: () => string | null;
  WebSocketImpl?: typeof WebSocket;
  heartbeatMs?: number;
  pongTimeoutMs?: number;
  reconnect?: { enabled?: boolean; baseDelayMs?: number; maxDelayMs?: number; maxAttempts?: number };
  onFrame: (frame: WsFrame) => void;
  onStateChange?: (state: ConnectionState) => void;
  onReconnected?: () => void;
  onError?: (error: ChatConnectionError) => void;
}

const DEFAULTS = {
  heartbeatMs: 25_000,
  pongTimeoutMs: 10_000,
  baseDelayMs: 600,
  maxDelayMs: 30_000,
  maxAttempts: Number.POSITIVE_INFINITY,
};

/**
 * WebSocket 连接管理：首帧 auth、心跳保活、指数退避重连。
 *
 * <p>重连成功后触发 {@code onReconnected}，由上层用 REST 游标补拉缺失消息。
 */
export class ChatSocket {
  private readonly options: Required<Pick<SocketOptions, 'heartbeatMs' | 'pongTimeoutMs'>> & SocketOptions;
  private socket: WebSocket | null = null;
  private state: ConnectionState = 'idle';
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private pongTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private attempts = 0;
  private manualClose = false;
  private everConnected = false;
  private readonly WebSocketImpl: typeof WebSocket;

  constructor(options: SocketOptions) {
    this.options = {
      ...options,
      heartbeatMs: options.heartbeatMs ?? DEFAULTS.heartbeatMs,
      pongTimeoutMs: options.pongTimeoutMs ?? DEFAULTS.pongTimeoutMs,
      reconnect: {
        enabled: options.reconnect?.enabled ?? true,
        baseDelayMs: options.reconnect?.baseDelayMs ?? DEFAULTS.baseDelayMs,
        maxDelayMs: options.reconnect?.maxDelayMs ?? DEFAULTS.maxDelayMs,
        maxAttempts: options.reconnect?.maxAttempts ?? DEFAULTS.maxAttempts,
      },
    };
    const impl = options.WebSocketImpl ?? globalThis.WebSocket;
    if (!impl) {
      throw new ChatConnectionError('当前环境没有 WebSocket 实现', 'ws_unavailable');
    }
    this.WebSocketImpl = impl;
  }

  get connectionState(): ConnectionState {
    return this.state;
  }

  connect(): void {
    if (this.state === 'connecting' || this.state === 'authenticating' || this.state === 'authenticated') {
      return;
    }
    const token = this.options.getToken();
    if (!token) {
      throw new ChatConnectionError('缺少 token，无法建立连接', 'unauthorized');
    }
    this.manualClose = false;
    this.setState('connecting');
    let socket: WebSocket;
    try {
      socket = new this.WebSocketImpl(this.options.url);
    } catch {
      this.scheduleReconnect();
      return;
    }
    this.socket = socket;
    socket.onopen = () => {
      this.setState('authenticating');
      this.sendRaw({ version: WS_PROTOCOL_VERSION, type: 'auth', payload: { token: this.options.getToken() } });
    };
    socket.onmessage = (event: MessageEvent) => this.handleMessage(event);
    socket.onerror = () => {
      // 瞬时错误由状态机（reconnecting）表达，避免给用户弹无意义的报错
      this.setState('reconnecting');
    };
    socket.onclose = () => {
      this.stopHeartbeat();
      this.socket = null;
      if (this.manualClose) {
        this.setState('closed');
        return;
      }
      this.setState('reconnecting');
      this.scheduleReconnect();
    };
  }

  disconnect(): void {
    this.manualClose = true;
    this.clearReconnect();
    this.stopHeartbeat();
    if (this.socket) {
      try {
        this.socket.close(1000, 'client disconnect');
      } catch {
        // 忽略关闭异常
      }
      this.socket = null;
    }
    this.setState('closed');
  }

  send(frame: Omit<WsFrame, 'version'> & { version?: number }): boolean {
    if (!this.socket || this.socket.readyState !== 1) {
      return false;
    }
    return this.sendRaw({ version: frame.version ?? WS_PROTOCOL_VERSION, ...frame });
  }

  private sendRaw(frame: WsFrame): boolean {
    if (!this.socket || this.socket.readyState !== 1) {
      return false;
    }
    try {
      this.socket.send(JSON.stringify(frame));
      return true;
    } catch {
      this.options.onError?.(new ChatConnectionError('发送失败，等待重连'));
      return false;
    }
  }

  private handleMessage(event: MessageEvent): void {
    const frame = parseWsFrame(typeof event.data === 'string' ? event.data : '');
    if (!frame) return;
    if (frame.type === 'auth.ok') {
      const wasReconnect = this.everConnected || this.attempts > 0;
      this.attempts = 0;
      this.everConnected = true;
      this.setState('authenticated');
      this.startHeartbeat();
      this.options.onFrame(frame);
      if (wasReconnect) {
        this.options.onReconnected?.();
      }
      return;
    }
    if (frame.type === 'pong') {
      this.clearPongTimer();
      return;
    }
    this.options.onFrame(frame);
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      const sent = this.sendRaw({ version: WS_PROTOCOL_VERSION, type: 'ping', payload: {} });
      if (!sent) return;
      this.clearPongTimer();
      this.pongTimer = setTimeout(() => {
        // 心跳超时：主动断开以触发重连
        try {
          this.socket?.close(4000, 'heartbeat timeout');
        } catch {
          // 忽略
        }
      }, this.options.pongTimeoutMs);
    }, this.options.heartbeatMs);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    this.clearPongTimer();
  }

  private clearPongTimer(): void {
    if (this.pongTimer) {
      clearTimeout(this.pongTimer);
      this.pongTimer = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.options.reconnect?.enabled === false) {
      this.setState('closed');
      return;
    }
    if (this.attempts >= (this.options.reconnect?.maxAttempts ?? DEFAULTS.maxAttempts)) {
      this.setState('closed');
      this.options.onError?.(new ChatConnectionError('实时连接已断开，请检查网络后刷新'));
      return;
    }
    this.attempts += 1;
    const base = this.options.reconnect?.baseDelayMs ?? DEFAULTS.baseDelayMs;
    const max = this.options.reconnect?.maxDelayMs ?? DEFAULTS.maxDelayMs;
    const backoff = Math.min(max, base * 2 ** (this.attempts - 1));
    const jitter = backoff * (0.5 + Math.random() * 0.5);
    this.clearReconnect();
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.manualClose) return;
      this.setState('reconnecting');
      this.connect();
    }, jitter);
  }

  private clearReconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private setState(state: ConnectionState): void {
    if (this.state === state) return;
    this.state = state;
    this.options.onStateChange?.(state);
  }
}
