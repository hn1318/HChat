import { ChatProvider, ChatWindow, MessageInput, useChat } from 'hchat-react-chat';
import { useEffect, useMemo, useRef, useState, type MutableRefObject } from 'react';
import 'hchat-react-chat/styles.css';
import { EmbedBridge, readParams, type EmbedParams } from './bridge';

interface EmbedApi {
  setToken: (token: string) => void;
  openConversation: (conversationId: string) => void;
}

export function EmbedApp() {
  const params = useMemo(() => readParams(window.location.search), []);
  const [theme, setTheme] = useState<EmbedParams['theme']>(params.theme);
  const [initialToken] = useState(params.token);
  const apiRef = useRef<EmbedApi | null>(null);

  const bridge = useMemo(
    () =>
      new EmbedBridge({
        allowedOrigins: params.hostOrigin,
        onTokenUpdate: (token) => apiRef.current?.setToken(token),
        onOpenConversation: (conversationId) => apiRef.current?.openConversation(conversationId),
        onThemeUpdate: (next) => setTheme(next),
      }),
    [params.hostOrigin],
  );

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  useEffect(() => () => bridge.destroy(), [bridge]);

  if (!initialToken) {
    return (
      <div className="embed-hint">
        <h2>缺少 token</h2>
        <p>iframe 地址需要携带 token 参数，或由宿主通过 postMessage 下发 auth.token.update。</p>
      </div>
    );
  }

  return (
    <ChatProvider
      baseUrl={params.baseUrl}
      token={initialToken}
      appId={params.appId}
      onError={(error) => bridge.emit('error', { message: error.message, code: error.code })}
    >
      <EmbedInner params={params} bridge={bridge} apiRef={apiRef} theme={theme} />
    </ChatProvider>
  );
}

function EmbedInner({
  params,
  bridge,
  apiRef,
  theme,
}: {
  params: EmbedParams;
  bridge: EmbedBridge;
  apiRef: MutableRefObject<EmbedApi | null>;
  theme: EmbedParams['theme'];
}) {
  const { client, openConversation, connectionState, currentUserId, activeConversation } = useChat();
  const bootstrapped = useRef(false);

  useEffect(() => {
    apiRef.current = {
      setToken: (token) => client.setToken(token),
      openConversation: (conversationId) => openConversation(conversationId),
    };
  }, [apiRef, client, openConversation]);

  useEffect(() => {
    if (connectionState === 'authenticated') {
      bridge.emit('ready', { appId: params.appId, mode: params.mode });
    }
  }, [bridge, connectionState, params.appId, params.mode]);

  // 建会话 / 打开指定会话
  useEffect(() => {
    if (bootstrapped.current) return;
    bootstrapped.current = true;
    if (params.conversationId) {
      openConversation(params.conversationId);
      return;
    }
    const metadata: Record<string, unknown> = {};
    if (params.productId) metadata.productId = params.productId;
    if (params.orderId) metadata.orderId = params.orderId;
    if (params.sellerId) metadata.sellerId = params.sellerId;
    const dedupeKey =
      params.dedupeKey ??
      (params.productId ? `${params.mode === 'c2c' ? 'p' : 'a'}:${params.productId}:${params.orderId ?? 'default'}` : undefined);

    void client.conversations
      .create({
        type: params.mode,
        metadata,
        dedupeKey,
        members: params.sellerId ? [{ externalUserId: params.sellerId, role: 'seller' }] : undefined,
      })
      .then((conversation) => {
        openConversation(conversation.id);
        bridge.emit('ready', { conversationId: conversation.id });
      })
      .catch((error: Error) => bridge.emit('error', { message: error.message }));
  }, [bridge, client, openConversation, params]);

  // 未读数上报
  useEffect(() => {
    const unsubscribe = client.on('message.new', ({ message }) => {
      if (message.senderId && message.senderId !== currentUserId) {
        bridge.emit('unread.count', { count: 1, conversationId: message.conversationId });
      }
      if (message.senderId === currentUserId) {
        bridge.emit('message.send', { message });
      }
    });
    return unsubscribe;
  }, [bridge, client, currentUserId]);

  // 切换会话后未读清零
  useEffect(() => {
    bridge.emit('unread.count', { count: 0, conversationId: activeConversation?.id });
  }, [bridge, activeConversation?.id]);

  return (
    <div className="hchat-root embed-root" data-theme={theme}>
      <ChatWindow showHeader={false} emptyText="正在准备会话…" />
      <MessageInput aiMode={params.mode === 'c2ai'} />
    </div>
  );
}
