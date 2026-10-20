<script setup lang="ts">
import { onMounted, onUnmounted, provide } from 'vue';
import type { ChatClient, ChatError } from 'hchat-core-sdk';
import { chatKey, createChatStore } from '../store';

const props = defineProps<{
  client?: ChatClient;
  baseUrl?: string;
  token?: string;
  appId?: string;
  tenantId?: string;
  initialConversationId?: string;
}>();

const emit = defineEmits<{ error: [ChatError] }>();

const store = createChatStore({
  client: props.client,
  baseUrl: props.baseUrl,
  token: props.token,
  appId: props.appId,
  tenantId: props.tenantId,
  initialConversationId: props.initialConversationId,
});

store.client.on('error', (err) => emit('error', err));
provide(chatKey, store);
onMounted(() => store.start());
onUnmounted(() => store.stop());

defineExpose({ store });
</script>

<template>
  <slot />
</template>
