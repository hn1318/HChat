<script setup lang="ts">
import { computed, onMounted, provide } from 'vue';
import type { Conversation } from 'hchat-core-sdk';
import { chatKey, createChatStore } from '../store';
import type { ChatWidgetProps, WidgetConversation } from '../types';
import ChatWindow from './ChatWindow.vue';
import MessageInput from './MessageInput.vue';

const props = withDefaults(defineProps<ChatWidgetProps>(), {
  mode: 'c2c',
  theme: 'auto',
  showHeader: true,
});

const emit = defineEmits<{ ready: [Conversation]; error: [Error] }>();

const store = createChatStore({
  client: props.client,
  baseUrl: props.baseUrl,
  token: props.token,
  appId: props.appId,
  tenantId: props.tenantId,
  initialConversationId: props.conversation?.conversationId,
});

provide(chatKey, store);

const style = computed(() => ({
  height: typeof props.height === 'number' ? `${props.height}px` : props.height,
}));

function bootstrap() {
  if (props.conversation?.conversationId) {
    store.openConversation(props.conversation.conversationId);
    return;
  }
  const metadata: Record<string, unknown> = { ...(props.conversation?.metadata ?? {}) };
  if (props.conversation?.productId) metadata.productId = props.conversation.productId;
  if (props.conversation?.orderId) metadata.orderId = props.conversation.orderId;
  if (props.conversation?.sellerId) metadata.sellerId = props.conversation.sellerId;

  const dedupeKey =
    props.conversation?.dedupeKey ??
    (props.conversation?.productId
      ? `${props.mode === 'c2c' ? 'p' : 'a'}:${props.conversation.productId}:${props.conversation.orderId ?? 'default'}`
      : undefined);

  void store.client.conversations
    .create({
      type: props.mode,
      title: props.conversation?.title,
      metadata,
      dedupeKey,
      agentId: props.conversation?.agentId,
      members: props.conversation?.sellerId
        ? [{ externalUserId: props.conversation.sellerId, role: 'seller' }]
        : undefined,
    })
    .then((conversation) => {
      store.openConversation(conversation.id);
      emit('ready', conversation);
    })
    .catch((error: Error) => emit('error', error));
}

onMounted(() => {
  store.start();
  bootstrap();
});
</script>

<template>
  <div class="hchat-root hchat-widget" :data-theme="props.theme" :style="style">
    <template v-if="store.activeConversation.value">
      <ChatWindow :show-header="props.showHeader" />
      <MessageInput :ai-mode="props.mode === 'c2ai'" />
    </template>
    <div v-else class="hchat-empty">正在创建会话…</div>
  </div>
</template>
