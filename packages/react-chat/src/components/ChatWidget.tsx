import type { ChatClient, Conversation } from 'hchat-core-sdk';
import { useEffect, useRef } from 'react';
import { ChatProvider, useChat } from '../context';
import { ChatWindow } from './ChatWindow';
import { MessageInput } from './MessageInput';

export interface WidgetConversation {
  conversationId?: string;
  title?: string;
  /** 去重键：同一 app 内相同值复用会话，例如 product_1001:order_9001 */
  dedupeKey?: string;
  productId?: string;
  orderId?: string;
  sellerId?: string;
  agentId?: string;
  metadata?: Record<string, unknown>;
}

export interface ChatWidgetProps {
  client?: ChatClient;
  baseUrl?: string;
  token: string;
  appId: string;
  tenantId?: string;
  mode?: 'c2c' | 'c2ai' | 'c2c_ai_assist';
  conversation?: WidgetConversation;
  theme?: 'light' | 'dark' | 'auto';
  height?: number | string;
  showHeader?: boolean;
  className?: string;
  onReady?: (conversation: Conversation) => void;
  onError?: (error: Error) => void;
}

function WidgetShell({
  mode = 'c2c',
  showHeader = true,
  height,
  className = '',
  conversation,
  onReady,
  onError,
}: Pick<ChatWidgetProps, 'mode' | 'showHeader' | 'height' | 'className' | 'conversation' | 'onReady' | 'onError'>) {
  const { client, openConversation, activeConversation } = useChat();
  const bootstrapped = useRef(false);

  useEffect(() => {
    if (conversation?.conversationId) {
      openConversation(conversation.conversationId);
      return;
    }
    if (bootstrapped.current) return;
    bootstrapped.current = true;

    const metadata: Record<string, unknown> = { ...(conversation?.metadata ?? {}) };
    if (conversation?.productId) metadata.productId = conversation.productId;
    if (conversation?.orderId) metadata.orderId = conversation.orderId;
    if (conversation?.sellerId) metadata.sellerId = conversation.sellerId;

    const dedupeKey =
      conversation?.dedupeKey ??
      (conversation?.productId
        ? `${mode === 'c2c' ? 'p' : 'a'}:${conversation.productId}:${conversation.orderId ?? 'default'}`
        : undefined);

    void client.conversations
      .create({
        type: mode,
        title: conversation?.title,
        metadata,
        dedupeKey,
        agentId: conversation?.agentId,
        members: conversation?.sellerId
          ? [{ externalUserId: conversation.sellerId, role: 'seller' }]
          : undefined,
      })
      .then((created) => {
        openConversation(created.id);
        onReady?.(created);
      })
      .catch((error: Error) => onError?.(error));
  }, [client, conversation, mode, onError, onReady, openConversation]);

  return (
    <div
      className={`hchat-root hchat-widget ${className}`}
      style={{ height: typeof height === 'number' ? `${height}px` : height }}
    >
      {activeConversation || conversation?.conversationId ? (
        <>
          <ChatWindow showHeader={showHeader} />
          <MessageInput aiMode={mode === 'c2ai'} />
        </>
      ) : (
        <div className="hchat-empty">正在创建会话…</div>
      )}
    </div>
  );
}

/**
 * 开箱即用的聊天挂件：自动建会话 + 消息窗口 + 输入框。
 *
 * ```tsx
 * <ChatWidget baseUrl="https://chat.example.com" appId="app_demo" token={token}
 *   conversation={{ productId: '1001', sellerId: 'seller_1' }} />
 * ```
 */
export function ChatWidget(props: ChatWidgetProps) {
  const { client, baseUrl, token, appId, tenantId, theme = 'auto', onError, ...shellProps } = props;
  return (
    <ChatProvider
      client={client}
      baseUrl={baseUrl}
      token={token}
      appId={appId}
      tenantId={tenantId}
      initialConversationId={props.conversation?.conversationId}
      onError={onError ? (error) => onError(error) : undefined}
    >
      <div className="hchat-root" data-theme={theme}>
        <WidgetShell {...shellProps} onError={onError} mode={props.mode} conversation={props.conversation} />
      </div>
    </ChatProvider>
  );
}
