export { ChatProvider, useChat } from './context';
export type { ChatContextValue, ChatProviderProps, SendMessageOptions } from './context';
export { ConversationList } from './components/ConversationList';
export type { ConversationListProps } from './components/ConversationList';
export { ChatWindow } from './components/ChatWindow';
export type { ChatWindowProps } from './components/ChatWindow';
export { MessageInput } from './components/MessageInput';
export type { MessageInputProps } from './components/MessageInput';
export { ChatWidget } from './components/ChatWidget';
export type { ChatWidgetProps, WidgetConversation } from './components/ChatWidget';

export { ChatError, createChatClient } from 'hchat-core-sdk';
export type {
  ChatAgent,
  ChatClient,
  ChatClientOptions,
  ConnectionState,
  Conversation,
  ConversationMember,
  CreateConversationInput,
  Message,
  MessageContent,
  MessageType,
  Page,
  SendMessageInput,
} from 'hchat-core-sdk';
