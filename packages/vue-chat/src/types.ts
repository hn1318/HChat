import type { ChatClient } from 'hchat-core-sdk';

export interface WidgetConversation {
  conversationId?: string;
  title?: string;
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
}
