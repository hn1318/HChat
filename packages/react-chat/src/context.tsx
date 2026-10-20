import {
  createChatClient,
  ChatError,
  type ChatClient,
  type ConnectionState,
  type Conversation,
  type Message,
  type MessageContent,
  type MessageType,
} from 'hchat-core-sdk';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

export interface ChatProviderProps {
  /** 外部传入的 client（与 baseUrl/token 二选一） */
  client?: ChatClient;
  baseUrl?: string;
  token?: string;
  appId?: string;
  tenantId?: string;
  initialConversationId?: string;
  onError?: (error: ChatError) => void;
  children: ReactNode;
}

export interface SendMessageOptions {
  text?: string;
  type?: MessageType;
  content?: MessageContent;
  file?: Blob;
}

export interface ChatContextValue {
  client: ChatClient;
  connectionState: ConnectionState;
  currentUserId: string | null;
  conversations: Conversation[];
  conversationsLoading: boolean;
  activeId: string | null;
  activeConversation: Conversation | null;
  messages: Message[];
  messagesLoading: boolean;
  hasMoreMessages: boolean;
  streamingText: string | null;
  typingUserIds: string[];
  error: ChatError | null;
  openConversation: (conversationId: string) => void;
  refreshConversations: () => Promise<void>;
  sendMessage: (options: SendMessageOptions) => Promise<void>;
  sendAiMessage: (text: string) => Promise<void>;
  loadMoreMessages: () => Promise<void>;
  recallMessage: (messageId: string) => Promise<void>;
  markRead: (messageId?: string) => void;
  setTyping: (isTyping: boolean) => void;
}

const ChatContext = createContext<ChatContextValue | null>(null);

function upsertConversation(list: Conversation[], next: Conversation): Conversation[] {
  const index = list.findIndex((item) => item.id === next.id);
  const merged = index >= 0 ? list.map((item) => (item.id === next.id ? { ...item, ...next } : item)) : [next, ...list];
  return merged.sort((a, b) => {
    const aTime = new Date(a.lastMessageAt ?? a.updatedAt ?? a.createdAt).getTime();
    const bTime = new Date(b.lastMessageAt ?? b.updatedAt ?? b.createdAt).getTime();
    return bTime - aTime;
  });
}

/**
 * 聊天上下文：负责数据加载、实时事件合并、已读上报。
 * 组件本身不依赖后端域名，全部通过 client 访问。
 */
export function ChatProvider(props: ChatProviderProps) {
  const { children, client: externalClient, baseUrl, token, appId, tenantId, initialConversationId, onError } = props;

  const ownsClient = !externalClient;
  const client = useMemo(() => {
    if (externalClient) return externalClient;
    if (!baseUrl || !token) {
      throw new ChatError('ChatProvider 需要 client，或者同时提供 baseUrl 与 token', {
        code: 'invalid_options',
      });
    }
    return createChatClient({ baseUrl, token, appId, tenantId, autoConnect: false });
  }, [externalClient, baseUrl, token, appId, tenantId]);

  const [connectionState, setConnectionState] = useState<ConnectionState>('idle');
  const [currentUserId, setCurrentUserId] = useState<string | null>(null);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [conversationsLoading, setConversationsLoading] = useState(true);
  const [activeId, setActiveId] = useState<string | null>(initialConversationId ?? null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [cursor, setCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [streamingText, setStreamingText] = useState<string | null>(null);
  const [typingMap, setTypingMap] = useState<Record<string, string[]>>({});
  const [error, setError] = useState<ChatError | null>(null);

  const activeIdRef = useRef<string | null>(activeId);
  const currentUserIdRef = useRef<string | null>(null);
  activeIdRef.current = activeId;
  currentUserIdRef.current = currentUserId;

  const reportError = useCallback(
    (err: unknown) => {
      const chatError =
        err instanceof ChatError ? err : new ChatError(err instanceof Error ? err.message : '未知错误');
      setError(chatError);
      onError?.(chatError);
    },
    [onError],
  );

  const refreshConversations = useCallback(async () => {
    try {
      const page = await client.conversations.list({ limit: 50 });
      setConversations((prev) => {
        // 列表接口不带 members，合并时保留已加载过的成员信息（否则头像/昵称会丢）
        const merged = page.items.map((item) => {
          const existing = prev.find((candidate) => candidate.id === item.id);
          return existing?.members ? { ...item, members: existing.members } : item;
        });
        // 保留未在首页但仍在本地的会话（例如刚创建的空会话）
        prev.forEach((item) => {
          if (!merged.some((next) => next.id === item.id)) merged.push(item);
        });
        return merged.sort((a, b) => {
          const aTime = new Date(a.lastMessageAt ?? a.updatedAt ?? a.createdAt).getTime();
          const bTime = new Date(b.lastMessageAt ?? b.updatedAt ?? b.createdAt).getTime();
          return bTime - aTime;
        });
      });
      setActiveId((prev) => prev ?? page.items[0]?.id ?? null);
    } catch (err) {
      reportError(err);
    } finally {
      setConversationsLoading(false);
    }
  }, [client, reportError]);

  const loadMessages = useCallback(
    async (conversationId: string) => {
      setMessagesLoading(true);
      try {
        const page = await client.messages.list(conversationId, { limit: 30 });
        setMessages(page.items);
        setCursor(page.nextCursor ?? null);
        setHasMore(page.hasMore);
      } catch (err) {
        reportError(err);
      } finally {
        setMessagesLoading(false);
      }
    },
    [client, reportError],
  );

  const markRead = useCallback(
    (messageId?: string) => {
      const conversationId = activeIdRef.current;
      if (!conversationId) return;
      void client.messages.markRead(conversationId, messageId).catch(() => undefined);
      setConversations((prev) =>
        prev.map((item) => (item.id === conversationId ? { ...item, unreadCount: 0 } : item)),
      );
    },
    [client],
  );

  const openConversation = useCallback(
    (conversationId: string) => {
      setActiveId(conversationId);
      setStreamingText(null);
      // 新建会话时列表里还没有它：主动拉一次详情并合并，避免「有 id 但没有会话对象」
      void client.conversations
        .get(conversationId)
        .then((conversation) => setConversations((prev) => upsertConversation(prev, conversation)))
        .catch(() => undefined);
    },
    [client],
  );

  // 首次加载
  useEffect(() => {
    void refreshConversations();
  }, [refreshConversations]);

  // 建立连接
  useEffect(() => {
    if (!ownsClient) return;
    client.connect();
    return () => client.disconnect();
  }, [client, ownsClient]);

  // 切换会话时加载消息
  useEffect(() => {
    if (!activeId) {
      setMessages([]);
      return;
    }
    void loadMessages(activeId).then(() => markRead());
  }, [activeId, loadMessages, markRead]);

  // 订阅实时事件
  useEffect(() => {
    const unsubscribers = [
      client.on('connection.state', setConnectionState),
      client.on('error', (err) => reportError(err)),
      client.on('auth.ok', (payload) => setCurrentUserId(payload.userId)),
      client.on('conversation.updated', ({ conversation }) => {
        setConversations((prev) => upsertConversation(prev, conversation));
      }),
      client.on('message.new', ({ message }) => {
        const isActive = message.conversationId === activeIdRef.current;
        setConversations((prev) => {
          const target = prev.find((item) => item.id === message.conversationId);
          if (!target) {
            void refreshConversations();
            return prev;
          }
          const preview =
            message.type === 'text'
              ? (message.content.text ?? '')
              : message.type === 'image'
                ? '[图片]'
                : message.type === 'file'
                  ? `[文件] ${message.content.fileName ?? ''}`
                  : message.type === 'card'
                    ? `[卡片] ${message.content.title ?? ''}`
                    : (message.content.text ?? '[系统消息]');
          const isMine = message.senderId === currentUserIdRef.current;
          return upsertConversation(prev, {
            ...target,
            lastMessageId: message.id,
            lastMessageAt: message.createdAt,
            lastMessagePreview: preview,
            unreadCount: isActive || isMine ? target.unreadCount : target.unreadCount + 1,
          });
        });
        if (isActive) {
          setMessages((prev) =>
            prev.some((item) => item.id === message.id)
              ? prev
              : [...prev, message].sort((a, b) => Number(a.id) - Number(b.id)),
          );
          if (message.senderType !== 'system' && message.senderId !== currentUserIdRef.current) {
            markRead(message.id);
          }
        }
      }),
      client.on('message.read', ({ conversationId, userId, messageId }) => {
        setConversations((prev) =>
          prev.map((item) => {
            if (item.id !== conversationId || !item.members) return item;
            return {
              ...item,
              members: item.members.map((member) =>
                member.userId === userId ? { ...member, lastReadMessageId: messageId } : member,
              ),
            };
          }),
        );
      }),
      client.on('message.recalled', ({ messageId, message }) => {
        setMessages((prev) =>
          prev.map((item) =>
            item.id === messageId
              ? { ...item, status: 'recalled', content: message?.content ?? item.content }
              : item,
          ),
        );
      }),
      client.on('typing', ({ conversationId, userId, isTyping }) => {
        setTypingMap((prev) => {
          const current = new Set(prev[conversationId] ?? []);
          if (isTyping) current.add(userId);
          else current.delete(userId);
          return { ...prev, [conversationId]: Array.from(current) };
        });
      }),
      client.on('reconnected', () => {
        void refreshConversations();
        const conversationId = activeIdRef.current;
        if (!conversationId) return;
        setMessages((prev) => {
          const lastId = prev[prev.length - 1]?.id;
          void client.messages
            .list(conversationId, lastId ? { after: lastId, limit: 100 } : { limit: 30 })
            .then((page) => {
              if (!lastId) {
                setMessages(page.items);
                return;
              }
              setMessages((current) => {
                const merged = [...current];
                page.items.forEach((message) => {
                  if (!merged.some((item) => item.id === message.id)) merged.push(message);
                });
                return merged.sort((a, b) => Number(a.id) - Number(b.id));
              });
            })
            .catch(() => undefined);
          return prev;
        });
      }),
    ];
    return () => unsubscribers.forEach((off) => off());
  }, [client, markRead, refreshConversations, reportError]);

  const sendMessage = useCallback(
    async ({ text, type, content, file }: SendMessageOptions) => {
      const conversationId = activeIdRef.current;
      if (!conversationId) return;
      try {
        if (file) {
          const uploaded = await client.files.upload(file);
          const isImage = (uploaded.mimeType ?? '').startsWith('image/');
          await client.messages.send(conversationId, {
            type: isImage ? 'image' : 'file',
            content: {
              url: uploaded.url,
              fileName: uploaded.fileName,
              mimeType: uploaded.mimeType,
              size: uploaded.size,
            },
          });
          return;
        }
        const messageType = type ?? 'text';
        const payload: MessageContent = content ?? { text: text ?? '' };
        await client.messages.send(conversationId, { type: messageType, content: payload });
      } catch (err) {
        reportError(err);
      }
    },
    [client, reportError],
  );

  const sendAiMessage = useCallback(
    async (text: string) => {
      const conversationId = activeIdRef.current;
      if (!conversationId) return;
      setStreamingText('');
      try {
        await client.ai.stream({
          conversationId,
          text,
          onDelta: (delta) => setStreamingText((prev) => (prev ?? '') + delta),
        });
      } catch (err) {
        reportError(err);
      } finally {
        setStreamingText(null);
      }
    },
    [client, reportError],
  );

  const loadMoreMessages = useCallback(async () => {
    const conversationId = activeIdRef.current;
    if (!conversationId || !cursor || !hasMore) return;
    try {
      const page = await client.messages.list(conversationId, { before: cursor, limit: 30 });
      setMessages((prev) => {
        const merged = [...page.items, ...prev];
        const seen = new Set<string>();
        return merged.filter((item) => (seen.has(item.id) ? false : seen.add(item.id)));
      });
      setCursor(page.nextCursor ?? null);
      setHasMore(page.hasMore);
    } catch (err) {
      reportError(err);
    }
  }, [client, cursor, hasMore, reportError]);

  const recallMessage = useCallback(
    async (messageId: string) => {
      try {
        await client.messages.recall(messageId);
      } catch (err) {
        reportError(err);
      }
    },
    [client, reportError],
  );

  const setTyping = useCallback(
    (isTyping: boolean) => {
      const conversationId = activeIdRef.current;
      if (conversationId) client.messages.typing(conversationId, isTyping);
    },
    [client],
  );

  const activeConversation = conversations.find((item) => item.id === activeId) ?? null;

  const value = useMemo<ChatContextValue>(
    () => ({
      client,
      connectionState,
      currentUserId,
      conversations,
      conversationsLoading,
      activeId,
      activeConversation,
      messages,
      messagesLoading,
      hasMoreMessages: hasMore,
      streamingText,
      typingUserIds: activeId ? (typingMap[activeId] ?? []) : [],
      error,
      openConversation,
      refreshConversations,
      sendMessage,
      sendAiMessage,
      loadMoreMessages,
      recallMessage,
      markRead,
      setTyping,
    }),
    [
      client,
      connectionState,
      currentUserId,
      conversations,
      conversationsLoading,
      activeId,
      activeConversation,
      messages,
      messagesLoading,
      hasMore,
      streamingText,
      typingMap,
      error,
      openConversation,
      refreshConversations,
      sendMessage,
      sendAiMessage,
      loadMoreMessages,
      recallMessage,
      markRead,
      setTyping,
    ],
  );

  return <ChatContext.Provider value={value}>{children}</ChatContext.Provider>;
}

export function useChat(): ChatContextValue {
  const context = useContext(ChatContext);
  if (!context) {
    throw new Error('useChat 必须在 <ChatProvider> 内使用');
  }
  return context;
}

export { ChatContext };
