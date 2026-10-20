<script setup lang="ts">
import { computed, ref } from 'vue';
import type { Conversation } from 'hchat-core-sdk';
import { useChat } from '../store';
import { avatarColor, formatTime, initials } from '../utils';

const props = withDefaults(
  defineProps<{
    emptyText?: string;
    showSearch?: boolean;
  }>(),
  { emptyText: '还没有会话', showSearch: true },
);

const emit = defineEmits<{ select: [Conversation] }>();

const store = useChat();
const keyword = ref('');

function titleOf(conversation: Conversation): string {
  if (conversation.title) return conversation.title;
  const other = conversation.members?.find((member) => member.userId !== store.currentUserId.value);
  if (other) return other.displayName ?? other.externalUserId ?? '会话';
  return conversation.type === 'c2ai' ? 'AI 助手' : `会话 ${conversation.id.slice(-6)}`;
}

const filtered = computed(() => {
  const trimmed = keyword.value.trim().toLowerCase();
  if (!trimmed) return store.conversations.value;
  return store.conversations.value.filter((conversation) => {
    const title = titleOf(conversation).toLowerCase();
    const preview = (conversation.lastMessagePreview ?? '').toLowerCase();
    return title.includes(trimmed) || preview.includes(trimmed);
  });
});

function select(conversation: Conversation) {
  store.openConversation(conversation.id);
  emit('select', conversation);
}
</script>

<template>
  <div class="hchat-conversations">
    <div v-if="props.showSearch" class="hchat-conversations__search">
      <input v-model="keyword" type="search" placeholder="搜索会话" aria-label="搜索会话" />
    </div>

    <div class="hchat-conversations__list" role="list">
      <div v-if="store.conversationsLoading.value && filtered.length === 0" class="hchat-empty">加载中…</div>
      <div v-else-if="filtered.length === 0" class="hchat-empty">{{ props.emptyText }}</div>

      <button
        v-for="conversation in filtered"
        :key="conversation.id"
        type="button"
        role="listitem"
        class="hchat-conversation"
        :class="{ 'is-active': conversation.id === store.activeId.value }"
        @click="select(conversation)"
      >
        <span class="hchat-avatar" :style="{ backgroundColor: avatarColor(conversation.id) }" aria-hidden="true">
          {{ initials(titleOf(conversation)) }}
        </span>
        <span class="hchat-conversation__body">
          <span class="hchat-conversation__top">
            <span class="hchat-conversation__title">{{ titleOf(conversation) }}</span>
            <span class="hchat-conversation__time">{{ formatTime(conversation.lastMessageAt) }}</span>
          </span>
          <span class="hchat-conversation__bottom">
            <span class="hchat-conversation__preview">
              {{ conversation.lastMessagePreview ?? '暂无消息' }}
            </span>
            <span v-if="conversation.unreadCount > 0" class="hchat-badge">
              {{ conversation.unreadCount > 99 ? '99+' : conversation.unreadCount }}
            </span>
          </span>
        </span>
      </button>
    </div>
  </div>
</template>
