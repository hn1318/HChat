/**
 * iframe ↔ 宿主 postMessage 协议。
 *
 * 宿主 → iframe：auth.token.update / conversation.open / theme.update
 * iframe → 宿主：ready / unread.count / navigate / message.send
 *
 * 所有消息都带 {@code source} 标记，并做 origin 白名单校验（hostOrigin 为空时才回退到 *）。
 */

export type EmbedToHostType = 'ready' | 'unread.count' | 'navigate' | 'message.send' | 'error';
export type HostToEmbedType = 'auth.token.update' | 'conversation.open' | 'theme.update';

interface Envelope<T> {
  source: 'hchat-embed' | 'hchat-host';
  type: string;
  payload?: T;
}

export interface BridgeHandlers {
  allowedOrigins: string[];
  onTokenUpdate?: (token: string) => void;
  onOpenConversation?: (conversationId: string) => void;
  onThemeUpdate?: (theme: 'light' | 'dark' | 'auto') => void;
}

const EMBED_SOURCE = 'hchat-embed';
const HOST_SOURCE = 'hchat-host';

export class EmbedBridge {
  private readonly handlers: BridgeHandlers;
  private readonly listener: (event: MessageEvent) => void;
  private targetOrigin: string;

  constructor(handlers: BridgeHandlers) {
    this.handlers = handlers;
    this.targetOrigin = handlers.allowedOrigins[0] ?? '*';
    this.listener = (event: MessageEvent) => this.handle(event);
    window.addEventListener('message', this.listener);
  }

  private allow(origin: string): boolean {
    return this.handlers.allowedOrigins.length === 0 || this.handlers.allowedOrigins.includes(origin);
  }

  private handle(event: MessageEvent): void {
    if (!this.allow(event.origin)) {
      return;
    }
    const data = event.data as Envelope<unknown> | null;
    if (!data || data.source !== HOST_SOURCE) {
      return;
    }
    // 记录宿主真实 origin，回消息时精确指定（而不是 *）
    this.targetOrigin = event.origin || this.targetOrigin;
    switch (data.type) {
      case 'auth.token.update': {
        const token = (data.payload as { token?: string } | undefined)?.token;
        if (token) this.handlers.onTokenUpdate?.(token);
        break;
      }
      case 'conversation.open': {
        const id = (data.payload as { conversationId?: string } | undefined)?.conversationId;
        if (id) this.handlers.onOpenConversation?.(id);
        break;
      }
      case 'theme.update': {
        const theme = (data.payload as { theme?: 'light' | 'dark' | 'auto' } | undefined)?.theme;
        if (theme) this.handlers.onThemeUpdate?.(theme);
        break;
      }
      default:
        break;
    }
  }

  emit(type: EmbedToHostType, payload?: unknown): void {
    const envelope: Envelope<unknown> = { source: EMBED_SOURCE, type, payload };
    try {
      window.parent?.postMessage(envelope, this.targetOrigin);
    } catch {
      // 跨域发送失败时忽略（宿主未监听）
    }
  }

  destroy(): void {
    window.removeEventListener('message', this.listener);
  }
}

export interface EmbedParams {
  baseUrl: string;
  appId: string;
  token: string;
  mode: 'c2c' | 'c2ai' | 'c2c_ai_assist';
  conversationId?: string;
  productId?: string;
  orderId?: string;
  sellerId?: string;
  dedupeKey?: string;
  theme: 'light' | 'dark' | 'auto';
  hostOrigin: string[];
}

export function readParams(search: string): EmbedParams {
  const params = new URLSearchParams(search);
  const theme = params.get('theme');
  return {
    baseUrl: params.get('baseUrl') ?? 'http://localhost:8080',
    appId: params.get('appId') ?? 'app_demo',
    token: params.get('token') ?? '',
    mode: (params.get('mode') as EmbedParams['mode'] | null) ?? 'c2c',
    conversationId: params.get('conversationId') ?? undefined,
    productId: params.get('productId') ?? undefined,
    orderId: params.get('orderId') ?? undefined,
    sellerId: params.get('sellerId') ?? undefined,
    dedupeKey: params.get('dedupeKey') ?? undefined,
    theme: theme === 'light' || theme === 'dark' ? theme : 'auto',
    hostOrigin: (params.get('hostOrigin') ?? '')
      .split(',')
      .map((value) => value.trim())
      .filter(Boolean),
  };
}
