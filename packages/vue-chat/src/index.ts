export { default as ChatProvider } from './components/ChatProvider.vue';
export { default as ConversationList } from './components/ConversationList.vue';
export { default as ChatWindow } from './components/ChatWindow.vue';
export { default as MessageInput } from './components/MessageInput.vue';
export { default as ChatWidget } from './components/ChatWidget.vue';

export { createChatStore, useChat, chatKey } from './store';
export type { ChatStore, ChatStoreOptions, SendOptions } from './store';
export type { ChatWidgetProps, WidgetConversation } from './types';

export { ChatError, createChatClient } from 'hchat-core-sdk';
export type {
  ChatClient,
  ChatClientOptions,
  Conversation,
  ConversationMember,
  Message,
  MessageContent,
  MessageType,
  Page,
} from 'hchat-core-sdk';
