import {
  ChatError,
  createChatClient,
  type ChatClient,
  type ConnectionState,
  type Conversation,
  type Message,
  type MessageContent,
  type MessageType,
} from 'hchat-core-sdk';
import { computed, inject, ref, type InjectionKey } from 'vue';

export interface ChatStoreOptions {
  client?: ChatClient;
  baseUrl?: string;
  token?: string;
  appId?: string;
  tenantId?: string;
  initialConversationId?: string;
}

export interface SendOptions {
  text?: string;
  type?: MessageType;
  content?: MessageContent;
  file?: Blob;
}

function sortConversations(list: Conversation[]): Conversation[] {
  return [...list].sort((a, b) => {
    const aTime = new Date(a.lastMessageAt ?? a.updatedAt ?? a.createdAt).getTime();
    const bTime = new Date(b.lastMessageAt ?? b.updatedAt ?? b.createdAt).getTime();
    return bTime - aTime;
  });
}

function upsert(list: Conversation[], next: Conversation): Conversation[] {
  const index = list.findIndex((item) => item.id === next.id);
  const merged =
    index >= 0 ? list.map((item) => (item.id === next.id ? { ...item, ...next } : item)) : [next, ...list];
  return sortConversations(merged);
}

function previewOf(message: Message): string {
  switch (message.type) {
    case 'image':
      return '[图片]';
    case 'file':
      return `[文件] ${message.content.fileName ?? ''}`;
    case 'card':
      return `[卡片] ${message.content.title ?? ''}`;
    case 'system':
      return message.content.text ?? '[系统消息]';
    default:
      return message.content.text ?? '';
  }
}

/**
 * 与 React 版本行为一致的聊天状态机（Vue 组合式 API）。
 */
export function createChatStore(options: ChatStoreOptions) {
  const ownsClient = !options.client;
  const client =
    options.client ??
    createChatClient({
      baseUrl: options.baseUrl ?? '',
      token: options.token ?? '',
      appId: options.appId,
      tenantId: options.tenantId,
      autoConnect: false,
    });

  const conversations = ref<Conversation[]>([]);
  const conversationsLoading = ref(true);
  const activeId = ref<string | null>(options.initialConversationId ?? null);
  const messages = ref<Message[]>([]);
  const messagesLoading = ref(false);
  const cursor = ref<string | null>(null);
  const hasMore = ref(false);
  const connectionState = ref<ConnectionState>('idle');
  const currentUserId = ref<string | null>(null);
  const streamingText = ref<string | null>(null);
  const typingMap = ref<Record<string, string[]>>({});
  const error = ref<ChatError | null>(null);

  const activeConversation = computed(
    () => conversations.value.find((item) => item.id === activeId.value) ?? null,
  );
  const typingUserIds = computed(() => (activeId.value ? (typingMap.value[activeId.value] ?? []) : []));

  const report = (err: unknown): void => {
    error.value = err instanceof ChatError ? err : new ChatError(err instanceof Error ? err.message : '未知错误');
  };

  const refreshConversations = async (): Promise<void> => {
    try {
      const page = await client.conversations.list({ limit: 50 });
      conversations.value = sortConversations(page.items);
      if (!activeId.value) {
        activeId.value = page.items[0]?.id ?? null;
      }
    } catch (err) {
      report(err);
    } finally {
      conversationsLoading.value = false;
    }
  };

  const loadMessages = async (conversationId: string): Promise<void> => {
    messagesLoading.value = true;
    try {
      const page = await client.messages.list(conversationId, { limit: 30 });
      messages.value = page.items;
      cursor.value = page.nextCursor ?? null;
      hasMore.value = page.hasMore;
    } catch (err) {
      report(err);
    } finally {
      messagesLoading.value = false;
    }
  };

  const markRead = (messageId?: string): void => {
    if (!activeId.value) return;
    void client.messages.markRead(activeId.value, messageId).catch(() => undefined);
    conversations.value = conversations.value.map((item) =>
      item.id === activeId.value ? { ...item, unreadCount: 0 } : item,
    );
  };

  const openConversation = (conversationId: string): void => {
    activeId.value = conversationId;
    streamingText.value = null;
    void loadMessages(conversationId).then(() => markRead());
  };

  const sendMessage = async ({ text, type, content, file }: SendOptions): Promise<void> => {
    if (!activeId.value) return;
    try {
      if (file) {
        const uploaded = await client.files.upload(file);
        const isImage = (uploaded.mimeType ?? '').startsWith('image/');
        await client.messages.send(activeId.value, {
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
      await client.messages.send(activeId.value, {
        type: type ?? 'text',
        content: content ?? { text: text ?? '' },
      });
    } catch (err) {
      report(err);
    }
  };

  const sendAiMessage = async (text: string): Promise<void> => {
    if (!activeId.value) return;
    streamingText.value = '';
    try {
      await client.ai.stream({
        conversationId: activeId.value,
        text,
        onDelta: (delta) => {
          streamingText.value = (streamingText.value ?? '') + delta;
        },
      });
    } catch (err) {
      report(err);
    } finally {
      streamingText.value = null;
    }
  };

  const loadMoreMessages = async (): Promise<void> => {
    if (!activeId.value || !cursor.value || !hasMore.value) return;
    try {
      const page = await client.messages.list(activeId.value, { before: cursor.value, limit: 30 });
      const seen = new Set<string>();
      messages.value = [...page.items, ...messages.value].filter((item) =>
        seen.has(item.id) ? false : (seen.add(item.id), true),
      );
      cursor.value = page.nextCursor ?? null;
      hasMore.value = page.hasMore;
    } catch (err) {
      report(err);
    }
  };

  const recallMessage = async (messageId: string): Promise<void> => {
    try {
      await client.messages.recall(messageId);
    } catch (err) {
      report(err);
    }
  };

  const setTyping = (isTyping: boolean): void => {
    if (activeId.value) client.messages.typing(activeId.value, isTyping);
  };

  const unsubscribers: Array<() => void> = [];
  let started = false;

  const start = (): void => {
    if (started) return;
    started = true;
    void refreshConversations();
    if (ownsClient) client.connect();

    unsubscribers.push(
      client.on('connection.state', (state) => (connectionState.value = state)),
      client.on('error', report),
      client.on('auth.ok', (payload) => (currentUserId.value = payload.userId)),
      client.on('conversation.updated', ({ conversation }) => {
        conversations.value = upsert(conversations.value, conversation);
      }),
      client.on('message.new', ({ message }) => {
        const isActive = message.conversationId === activeId.value;
        const target = conversations.value.find((item) => item.id === message.conversationId);
        if (!target) {
          void refreshConversations();
          return;
        }
        const isMine = message.senderId === currentUserId.value;
        conversations.value = upsert(conversations.value, {
          ...target,
          lastMessageId: message.id,
          lastMessageAt: message.createdAt,
          lastMessagePreview: previewOf(message),
          unreadCount: isActive || isMine ? target.unreadCount : target.unreadCount + 1,
        });
        if (isActive) {
          if (!messages.value.some((item) => item.id === message.id)) {
            messages.value = [...messages.value, message];
          }
          if (message.senderId && message.senderId !== currentUserId.value) {
            markRead(message.id);
          }
        }
      }),
      client.on('message.read', ({ conversationId, userId, messageId }) => {
        conversations.value = conversations.value.map((item) => {
          if (item.id !== conversationId || !item.members) return item;
          return {
            ...item,
            members: item.members.map((member) =>
              member.userId === userId ? { ...member, lastReadMessageId: messageId } : member,
            ),
          };
        });
      }),
      client.on('message.recalled', ({ messageId }) => {
        messages.value = messages.value.map((item) =>
          item.id === messageId ? { ...item, status: 'recalled' } : item,
        );
      }),
      client.on('typing', ({ conversationId, userId, isTyping }) => {
        const current = new Set(typingMap.value[conversationId] ?? []);
        if (isTyping) current.add(userId);
        else current.delete(userId);
        typingMap.value = { ...typingMap.value, [conversationId]: Array.from(current) };
      }),
      client.on('reconnected', () => {
        void refreshConversations();
        if (activeId.value) void loadMessages(activeId.value);
      }),
    );

    if (activeId.value) {
      void loadMessages(activeId.value).then(() => markRead());
    }
  };

  const stop = (): void => {
    unsubscribers.forEach((off) => off());
    unsubscribers.length = 0;
    if (ownsClient) client.disconnect();
    started = false;
  };

  return {
    client,
    conversations,
    conversationsLoading,
    activeId,
    activeConversation,
    messages,
    messagesLoading,
    hasMoreMessages: hasMore,
    connectionState,
    currentUserId,
    streamingText,
    typingUserIds,
    error,
    refreshConversations,
    openConversation,
    sendMessage,
    sendAiMessage,
    loadMoreMessages,
    recallMessage,
    markRead,
    setTyping,
    start,
    stop,
  };
}

export type ChatStore = ReturnType<typeof createChatStore>;

export const chatKey: InjectionKey<ChatStore> = Symbol('hchat');

export function useChat(): ChatStore {
  const store = inject(chatKey, null);
  if (!store) {
    throw new Error('useChat 必须在 <ChatProvider> 内使用');
  }
  return store;
}
