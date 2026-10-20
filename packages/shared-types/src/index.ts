/**
 * HChat 共享类型与运行时校验。
 *
 * 约定：
 * - 所有 ID 均为字符串（服务端雪花 ID 超过 JS 安全整数范围）
 * - 时间均为 ISO-8601 UTC 字符串
 * - REST 前缀 /api/v1，WebSocket 事件带 version 字段
 */
import { z } from 'zod';

export const WS_PROTOCOL_VERSION = 1;
export const API_PREFIX = '/api/v1';

export type ConversationType = 'c2c' | 'c2ai' | 'c2c_ai_assist';
export type MemberRole = 'buyer' | 'seller' | 'agent' | 'member';
export type MessageType = 'text' | 'image' | 'file' | 'card' | 'system';
export type MessageStatus = 'sent' | 'deleted' | 'recalled';
export type SenderType = 'user' | 'ai' | 'system';

/** 消息内容：按 type 取用不同字段，允许业务方追加自定义字段。 */
export interface MessageContent {
  text?: string;
  url?: string;
  fileName?: string;
  mimeType?: string;
  size?: number;
  width?: number;
  height?: number;
  title?: string;
  subtitle?: string;
  imageUrl?: string;
  price?: string;
  fields?: Array<{ label: string; value: string }>;
  [key: string]: unknown;
}

export interface ConversationMember {
  userId: string;
  externalUserId?: string | null;
  displayName?: string | null;
  avatarUrl?: string | null;
  role: MemberRole;
  lastReadMessageId?: string | null;
  unreadCount: number;
}

export interface Conversation {
  id: string;
  type: ConversationType;
  title?: string | null;
  metadata?: Record<string, unknown> | null;
  lastMessageId?: string | null;
  lastMessageAt?: string | null;
  lastMessagePreview?: string | null;
  unreadCount: number;
  members?: ConversationMember[] | null;
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  id: string;
  conversationId: string;
  senderType: SenderType;
  senderId?: string | null;
  type: MessageType;
  content: MessageContent;
  clientMsgId?: string | null;
  status: MessageStatus;
  createdAt: string;
}

export interface Page<T> {
  items: T[];
  nextCursor?: string | null;
  hasMore: boolean;
}

export interface ChatAgent {
  id: string;
  name: string;
  provider: string;
  model?: string | null;
  systemPrompt?: string | null;
  status: string;
}

export interface TokenResponse {
  token: string;
  tokenType: string;
  expiresIn: number;
  expiresAt: string;
  userId: string;
  externalUserId: string;
  appId: string;
  tenantId: string;
  scopes: string[];
}

export interface UploadResult {
  id: string;
  url: string;
  fileName: string;
  mimeType: string;
  size: number;
}

export interface ApiErrorBody {
  error: {
    code: string;
    message: string;
    requestId?: string;
    details?: Record<string, unknown> | null;
  };
}

/* ----------------------------- REST 入参 ----------------------------- */

export interface CreateConversationInput {
  type?: ConversationType;
  title?: string | null;
  metadata?: Record<string, unknown>;
  members?: Array<{ externalUserId: string; role?: MemberRole; displayName?: string }>;
  agentId?: string | null;
  /** 业务幂等键：同一 app 内相同值复用同一会话（如 productId:orderId） */
  dedupeKey?: string | null;
}

export interface SendMessageInput {
  type?: MessageType;
  content: MessageContent;
  clientMsgId?: string;
}

export interface AiStreamInput {
  text: string;
  agentId?: string | null;
  clientMsgId?: string;
  persistUserMessage?: boolean;
}

export interface AiStreamDone {
  runId: string;
  message: Message;
  usage?: Record<string, number>;
}

/* --------------------------- WebSocket 契约 --------------------------- */

export interface WsFrame<T = unknown> {
  version: number;
  type: string;
  payload: T;
}

export interface AuthOkPayload {
  userId: string;
  appId: string;
  tenantId: string;
  externalUserId: string;
  scopes: string[];
}

export interface MessageNewPayload {
  message: Message;
}

export interface MessageReadPayload {
  conversationId: string;
  userId: string;
  messageId: string;
}

export interface MessageRecalledPayload {
  messageId: string;
  conversationId: string;
  message?: Message;
}

export interface ConversationUpdatedPayload {
  conversation: Conversation;
}

export interface PresencePayload {
  userId: string;
  online: boolean;
}

export interface TypingPayload {
  conversationId: string;
  userId: string;
  isTyping: boolean;
}

export interface WsErrorPayload {
  code: string;
  message: string;
}

/** 服务端 → 客户端事件名。 */
export const ServerEventType = {
  authOk: 'auth.ok',
  messageNew: 'message.new',
  messageRead: 'message.read',
  messageRecalled: 'message.recalled',
  conversationUpdated: 'conversation.updated',
  presence: 'presence',
  typing: 'typing',
  pong: 'pong',
  error: 'error',
} as const;

/** 客户端 → 服务端事件名。 */
export const ClientEventType = {
  auth: 'auth',
  messageSend: 'message.send',
  messageRead: 'message.read',
  typing: 'typing',
  ping: 'ping',
} as const;

/* ----------------------------- Zod 校验 ----------------------------- */

export const wsFrameSchema = z.object({
  version: z.number().int().positive(),
  type: z.string(),
  payload: z.unknown(),
});

export const messageContentSchema = z
  .object({
    text: z.string().optional(),
    url: z.string().optional(),
    fileName: z.string().optional(),
    mimeType: z.string().optional(),
    size: z.number().optional(),
    title: z.string().optional(),
  })
  .passthrough();

export const messageSchema = z.object({
  id: z.string(),
  conversationId: z.string(),
  senderType: z.enum(['user', 'ai', 'system']),
  senderId: z.string().nullish(),
  type: z.enum(['text', 'image', 'file', 'card', 'system']),
  content: messageContentSchema,
  clientMsgId: z.string().nullish(),
  status: z.enum(['sent', 'deleted', 'recalled']),
  createdAt: z.string(),
});

export const conversationMemberSchema = z.object({
  userId: z.string(),
  externalUserId: z.string().nullish(),
  displayName: z.string().nullish(),
  avatarUrl: z.string().nullish(),
  role: z.enum(['buyer', 'seller', 'agent', 'member']),
  lastReadMessageId: z.string().nullish(),
  unreadCount: z.number(),
});

export const conversationSchema = z.object({
  id: z.string(),
  type: z.enum(['c2c', 'c2ai', 'c2c_ai_assist']),
  title: z.string().nullish(),
  metadata: z.record(z.unknown()).nullish(),
  lastMessageId: z.string().nullish(),
  lastMessageAt: z.string().nullish(),
  lastMessagePreview: z.string().nullish(),
  unreadCount: z.number(),
  members: z.array(conversationMemberSchema).nullish(),
  createdAt: z.string(),
  updatedAt: z.string(),
});

export const apiErrorSchema = z.object({
  error: z.object({
    code: z.string(),
    message: z.string(),
    requestId: z.string().optional(),
    details: z.record(z.unknown()).nullish(),
  }),
});

/** 安全解析一帧 WS 数据（非法帧返回 null，不抛异常）。 */
export function parseWsFrame(raw: string): WsFrame | null {
  try {
    const parsed = wsFrameSchema.safeParse(JSON.parse(raw));
    return parsed.success ? (parsed.data as WsFrame) : null;
  } catch {
    return null;
  }
}
