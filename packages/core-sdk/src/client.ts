import {
  type AiStreamDone,
  type AuthOkPayload,
  type ChatAgent,
  type Conversation,
  type ConversationMember,
  type ConversationUpdatedPayload,
  type CreateConversationInput,
  type Message,
  type MessageNewPayload,
  type MessageReadPayload,
  type MessageRecalledPayload,
  type Page,
  type PresencePayload,
  type SendMessageInput,
  type TypingPayload,
  type UploadResult,
  type WsFrame,
  type WsErrorPayload,
} from 'hchat-shared-types';
import { Emitter } from './emitter';
import { ChatError } from './errors';
import { HttpClient } from './http';
import { consumeSse } from './sse';
import { ChatSocket, type ConnectionState } from './socket';

export interface ChatClientOptions {
  /** 服务端地址，例如 http://localhost:8080 */
  baseUrl: string;
  /** 业务方后端签发的短期 Token（前端绝不持有 appSecret） */
  token: string;
  appId?: string;
  tenantId?: string;
  fetch?: typeof fetch;
  WebSocket?: typeof WebSocket;
  /** 创建后自动建立 WebSocket（默认 true） */
  autoConnect?: boolean;
  heartbeatMs?: number;
  reconnect?: { enabled?: boolean; baseDelayMs?: number; maxDelayMs?: number; maxAttempts?: number };
  /** 收到 401 时回调，便于上层刷新 Token */
  onUnauthorized?: (error: ChatError) => void;
}

export type ChatClientEvents = {
  'auth.ok': AuthOkPayload;
  'message.new': MessageNewPayload;
  'message.read': MessageReadPayload;
  'message.recalled': MessageRecalledPayload;
  'conversation.updated': ConversationUpdatedPayload;
  presence: PresencePayload;
  typing: TypingPayload;
  'connection.state': ConnectionState;
  reconnected: undefined;
  error: ChatError;
  'ai.delta': { runId: string; delta: string };
  'ai.done': AiStreamDone;
};

export interface ConversationListQuery {
  cursor?: string | null;
  limit?: number;
}

export interface MessageListQuery {
  before?: string | null;
  after?: string | null;
  limit?: number;
}

export interface AiStreamOptions {
  conversationId: string;
  text: string;
  agentId?: string | null;
  clientMsgId?: string;
  persistUserMessage?: boolean;
  signal?: AbortSignal;
  onDelta?: (delta: string, runId: string) => void;
}

function newId(): string {
  const cryptoObj = globalThis.crypto;
  if (cryptoObj && typeof cryptoObj.randomUUID === 'function') {
    return cryptoObj.randomUUID();
  }
  return `c_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * HChat 客户端：REST + WebSocket + SSE，框架无关。
 *
 * ```ts
 * const client = createChatClient({ baseUrl, token });
 * client.on('message.new', ({ message }) => ...);
 * await client.messages.send(conversationId, { content: { text: 'hi' } });
 * ```
 */
export class ChatClient {
  readonly events = new Emitter<ChatClientEvents>();
  readonly conversations: {
    list: (query?: ConversationListQuery) => Promise<Page<Conversation>>;
    get: (conversationId: string) => Promise<Conversation>;
    create: (input: CreateConversationInput) => Promise<Conversation>;
    addMember: (
      conversationId: string,
      input: { externalUserId: string; role?: ConversationMember['role'] },
    ) => Promise<Conversation>;
  };
  readonly messages: {
    list: (conversationId: string, query?: MessageListQuery) => Promise<Page<Message>>;
    send: (conversationId: string, input: SendMessageInput) => Promise<Message>;
    markRead: (conversationId: string, messageId?: string) => Promise<void>;
    recall: (messageId: string) => Promise<Message>;
    typing: (conversationId: string, isTyping: boolean) => void;
  };
  readonly files: {
    upload: (file: Blob, fileName?: string) => Promise<UploadResult>;
  };
  readonly ai: {
    listAgents: () => Promise<ChatAgent[]>;
    createAgent: (input: {
      name: string;
      provider?: string;
      model?: string;
      systemPrompt?: string;
      config?: Record<string, unknown>;
    }) => Promise<ChatAgent>;
    stream: (options: AiStreamOptions) => Promise<AiStreamDone>;
  };

  private readonly http: HttpClient;
  private readonly socket: ChatSocket;
  private token: string;
  private readonly autoConnect: boolean;
  private readonly onUnauthorized?: (error: ChatError) => void;

  constructor(options: ChatClientOptions) {
    if (!options.baseUrl) {
      throw new ChatError('baseUrl 不能为空', { code: 'invalid_options' });
    }
    this.token = options.token;
    this.autoConnect = options.autoConnect ?? true;
    this.onUnauthorized = options.onUnauthorized;

    this.http = new HttpClient({
      baseUrl: options.baseUrl,
      getToken: () => this.token,
      fetchImpl: options.fetch,
    });

    this.socket = new ChatSocket({
      url: this.http.wsUrl('/ws'),
      getToken: () => this.token,
      WebSocketImpl: options.WebSocket,
      heartbeatMs: options.heartbeatMs,
      reconnect: options.reconnect,
      onFrame: (frame) => this.handleFrame(frame),
      onStateChange: (state) => this.events.emit('connection.state', state),
      onReconnected: () => this.events.emit('reconnected', undefined),
      onError: (error) => this.events.emit('error', error),
    });

    this.conversations = {
      list: (query = {}) =>
        this.http.request<Page<Conversation>>('GET', '/api/v1/conversations', {
          query: { cursor: query.cursor ?? undefined, limit: query.limit },
        }),
      get: (conversationId) =>
        this.http.request<Conversation>('GET', `/api/v1/conversations/${conversationId}`),
      create: (input) =>
        this.http.request<Conversation>('POST', '/api/v1/conversations', {
          body: {
            type: input.type ?? 'c2c',
            title: input.title ?? undefined,
            metadata: input.metadata,
            members: input.members,
            agentId: input.agentId ?? undefined,
            dedupeKey: input.dedupeKey ?? undefined,
          },
        }),
      addMember: (conversationId, input) =>
        this.http.request<Conversation>('POST', `/api/v1/conversations/${conversationId}/members`, {
          body: input,
        }),
    };

    this.messages = {
      list: (conversationId, query = {}) =>
        this.http.request<Page<Message>>('GET', `/api/v1/conversations/${conversationId}/messages`, {
          query: { before: query.before ?? undefined, after: query.after ?? undefined, limit: query.limit },
        }),
      send: (conversationId, input) =>
        this.http
          .request<Message>('POST', `/api/v1/conversations/${conversationId}/messages`, {
            body: {
              clientMsgId: input.clientMsgId ?? newId(),
              type: input.type ?? 'text',
              content: input.content,
            },
          })
          .catch((error: unknown) => {
            this.handleHttpError(error);
            throw error;
          }),
      markRead: (conversationId, messageId) =>
        this.http.request<void>('POST', `/api/v1/conversations/${conversationId}/read`, {
          body: messageId ? { messageId } : {},
        }),
      recall: (messageId) => this.http.request<Message>('POST', `/api/v1/messages/${messageId}/recall`),
      typing: (conversationId, isTyping) => {
        this.socket.send({
          type: 'typing',
          payload: { conversationId, isTyping },
        });
      },
    };

    this.files = {
      upload: (file, fileName) => this.http.upload(file, fileName),
    };

    this.ai = {
      listAgents: () => this.http.request<ChatAgent[]>('GET', '/api/v1/ai/agents'),
      createAgent: (input) => this.http.request<ChatAgent>('POST', '/api/v1/ai/agents', { body: input }),
      stream: (streamOptions) => this.streamAi(streamOptions),
    };

    if (this.autoConnect) {
      this.connect();
    }
  }

  get connectionState(): ConnectionState {
    return this.socket.connectionState;
  }

  get currentToken(): string {
    return this.token;
  }

  /** Token 过期后由上层刷新，重连时会使用新 Token。 */
  setToken(token: string): void {
    this.token = token;
    if (this.connectionState === 'authenticated') {
      this.socket.send({ type: 'auth', payload: { token } });
    }
  }

  connect(): void {
    this.socket.connect();
  }

  disconnect(): void {
    this.socket.disconnect();
  }

  on<K extends keyof ChatClientEvents>(type: K, handler: (payload: ChatClientEvents[K]) => void): () => void {
    return this.events.on(type, handler);
  }

  once<K extends keyof ChatClientEvents>(type: K, handler: (payload: ChatClientEvents[K]) => void): () => void {
    return this.events.once(type, handler);
  }

  off<K extends keyof ChatClientEvents>(type: K, handler: (payload: ChatClientEvents[K]) => void): void {
    this.events.off(type, handler);
  }

  private handleFrame(frame: WsFrame): void {
    switch (frame.type) {
      case 'auth.ok':
        this.events.emit('auth.ok', frame.payload as AuthOkPayload);
        break;
      case 'message.new':
        this.events.emit('message.new', frame.payload as MessageNewPayload);
        break;
      case 'message.read':
        this.events.emit('message.read', frame.payload as MessageReadPayload);
        break;
      case 'message.recalled':
        this.events.emit('message.recalled', frame.payload as MessageRecalledPayload);
        break;
      case 'conversation.updated':
        this.events.emit('conversation.updated', frame.payload as ConversationUpdatedPayload);
        break;
      case 'presence':
        this.events.emit('presence', frame.payload as PresencePayload);
        break;
      case 'typing':
        this.events.emit('typing', frame.payload as TypingPayload);
        break;
      case 'error': {
        const payload = frame.payload as WsErrorPayload;
        this.events.emit('error', new ChatError(payload.message, { code: payload.code }));
        break;
      }
      default:
        break;
    }
  }

  private async streamAi(options: AiStreamOptions): Promise<AiStreamDone> {
    const response = await this.http.openStream(
      `/api/v1/conversations/${options.conversationId}/ai/stream`,
      {
        text: options.text,
        agentId: options.agentId ?? undefined,
        clientMsgId: options.clientMsgId,
        persistUserMessage: options.persistUserMessage,
      },
      options.signal,
    );

    const state: { done: AiStreamDone | null } = { done: null };
    await consumeSse(response, (event, data) => {
      let payload: unknown;
      try {
        payload = JSON.parse(data);
      } catch {
        return;
      }
      if (event === 'ai.delta') {
        const delta = payload as { runId: string; delta: string };
        this.events.emit('ai.delta', delta);
        options.onDelta?.(delta.delta, delta.runId);
      } else if (event === 'ai.done') {
        state.done = payload as AiStreamDone;
        this.events.emit('ai.done', state.done);
      } else if (event === 'error') {
        const error = payload as WsErrorPayload;
        throw new ChatError(error.message, { code: error.code });
      }
    });

    if (!state.done) {
      throw new ChatError('AI 流式响应中没有 ai.done 事件', { code: 'ai_stream_incomplete' });
    }
    return state.done;
  }

  private handleHttpError(error: unknown): void {
    if (error instanceof ChatError && error.status === 401) {
      this.events.emit('error', error);
      this.onUnauthorized?.(error);
    }
  }
}

export function createChatClient(options: ChatClientOptions): ChatClient {
  return new ChatClient(options);
}
