export { ChatClient, createChatClient } from './client';
export type {
  AiStreamOptions,
  ChatClientEvents,
  ChatClientOptions,
  ConversationListQuery,
  MessageListQuery,
} from './client';
export { ChatError, ChatConnectionError } from './errors';
export { Emitter } from './emitter';
export type { ConnectionState } from './socket';
export { consumeSse } from './sse';
export type {
  AiStreamDone,
  AuthOkPayload,
  ChatAgent,
  Conversation,
  ConversationMember,
  ConversationType,
  CreateConversationInput,
  MemberRole,
  Message,
  MessageContent,
  MessageNewPayload,
  MessageReadPayload,
  MessageRecalledPayload,
  MessageStatus,
  MessageType,
  Page,
  PresencePayload,
  SendMessageInput,
  SenderType,
  TypingPayload,
  UploadResult,
  WsFrame,
} from 'hchat-shared-types';
