<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import type { Message } from 'hchat-core-sdk';
import { useChat } from '../store';
import { avatarColor, compareId, formatDay, formatTime, initials, memberName, shouldShowTimestamp } from '../utils';

const props = withDefaults(defineProps<{ showHeader?: boolean; emptyText?: string }>(), {
  showHeader: true,
  emptyText: '选择一个会话开始聊天',
});

const store = useChat();
const listRef = ref<HTMLDivElement | null>(null);
const nearBottom = ref(true);

const members = computed(() => store.activeConversation.value?.members ?? []);

const title = computed(() => {
  const conversation = store.activeConversation.value;
  if (!conversation) return '';
  if (conversation.title) return conversation.title;
  const other = conversation.members?.find((member) => member.userId !== store.currentUserId.value);
  return other?.displayName ?? other?.externalUserId ?? (conversation.type === 'c2ai' ? 'AI 助手' : '会话');
});

const typingNames = computed(() =>
  store.typingUserIds.value
    .filter((id) => id !== store.currentUserId.value)
    .map((id) => memberName(members.value, id) || '对方')
    .join('、'),
);

const readMarker = computed(() => {
  const others = members.value.filter((member) => member.userId !== store.currentUserId.value);
  const ids = others
    .map((member) => member.lastReadMessageId)
    .filter((id): id is string => Boolean(id))
    .sort(compareId);
  return ids[0] ?? null;
});

function isOwn(message: Message): boolean {
  return Boolean(message.senderId) && message.senderId === store.currentUserId.value;
}

function senderName(message: Message): string {
  if (message.senderType === 'ai') return 'AI 助手';
  return memberName(members.value, message.senderId);
}

function onScroll() {
  const element = listRef.value;
  if (!element) return;
  nearBottom.value = element.scrollHeight - element.scrollTop - element.clientHeight < 120;
}

function scrollToBottom() {
  const element = listRef.value;
  if (element && nearBottom.value) {
    element.scrollTop = element.scrollHeight;
  }
}

watch(
  () => [store.messages.value.length, store.streamingText.value, store.activeId.value],
  () => nextTick(scrollToBottom),
);
</script>

<template>
  <div class="hchat-window">
    <header v-if="props.showHeader" class="hchat-window__header">
      <div class="hchat-window__title">{{ title }}</div>
      <div class="hchat-window__meta">
        <span class="hchat-dot" :class="`hchat-dot--${store.connectionState.value}`" aria-hidden="true" />
        <span v-if="store.connectionState.value !== 'authenticated'">
          {{ store.connectionState.value === 'reconnecting' ? '重连中…' : '离线' }}
        </span>
        <span v-else>{{ typingNames ? `${typingNames} 正在输入…` : '在线' }}</span>
      </div>
    </header>

    <div v-if="!store.activeConversation.value" class="hchat-empty">{{ props.emptyText }}</div>

    <div v-else ref="listRef" class="hchat-window__messages" @scroll="onScroll">
      <button
        v-if="store.hasMoreMessages.value"
        type="button"
        class="hchat-load-more"
        @click="store.loadMoreMessages()"
      >
        加载更多
      </button>

      <div v-if="store.messagesLoading.value && store.messages.value.length === 0" class="hchat-empty">加载中…</div>
      <div
        v-else-if="store.messages.value.length === 0 && store.streamingText.value === null"
        class="hchat-empty"
      >
        还没有消息，发第一条吧
      </div>

      <template v-for="(message, index) in store.messages.value" :key="message.id">
        <div
          v-if="index === 0 || formatDay(store.messages.value[index - 1]?.createdAt) !== formatDay(message.createdAt)"
          class="hchat-day"
        >
          {{ formatDay(message.createdAt) }}
        </div>

        <div v-if="message.type === 'system'" class="hchat-system">
          <span>{{ message.content.text }}</span>
        </div>

        <div v-else class="hchat-row" :class="{ 'hchat-row--own': isOwn(message) }">
          <span
            v-if="!isOwn(message)"
            class="hchat-avatar hchat-avatar--sm"
            :style="{ backgroundColor: avatarColor(message.senderId ?? message.id) }"
            aria-hidden="true"
          >
            {{ initials(senderName(message) || '?') }}
          </span>
          <div class="hchat-row__body">
            <div v-if="!isOwn(message) && senderName(message)" class="hchat-row__name">
              {{ senderName(message) }}
            </div>

            <div v-if="message.status === 'recalled'" class="hchat-bubble hchat-bubble--recalled">消息已撤回</div>

            <div v-else class="hchat-bubble" :class="{ 'hchat-bubble--own': isOwn(message) }">
              <a
                v-if="message.type === 'image' && message.content.url"
                class="hchat-bubble__image"
                :href="String(message.content.url)"
                target="_blank"
                rel="noreferrer noopener"
              >
                <img :src="String(message.content.url)" :alt="String(message.content.fileName ?? '图片')" loading="lazy" />
              </a>

              <a
                v-else-if="message.type === 'file' && message.content.url"
                class="hchat-bubble__file"
                :href="String(message.content.url)"
                target="_blank"
                rel="noreferrer noopener"
              >
                <span class="hchat-bubble__file-icon" aria-hidden="true">📎</span>
                <span class="hchat-bubble__file-meta">
                  <span class="hchat-bubble__file-name">{{ message.content.fileName ?? '文件' }}</span>
                  <span class="hchat-bubble__file-size">
                    {{ message.content.size ? `${Math.max(1, Math.round(Number(message.content.size) / 1024))} KB` : '下载' }}
                  </span>
                </span>
              </a>

              <div v-else-if="message.type === 'card'" class="hchat-bubble__card">
                <img v-if="message.content.imageUrl" :src="String(message.content.imageUrl)" :alt="message.content.title ?? '卡片'" />
                <div class="hchat-bubble__card-body">
                  <div class="hchat-bubble__card-title">{{ message.content.title ?? '卡片消息' }}</div>
                  <div v-if="message.content.subtitle" class="hchat-bubble__card-subtitle">
                    {{ message.content.subtitle }}
                  </div>
                  <div v-if="message.content.price" class="hchat-bubble__card-price">
                    {{ message.content.price }}
                  </div>
                  <div
                    v-for="field in message.content.fields ?? []"
                    :key="`${field.label}-${field.value}`"
                    class="hchat-bubble__card-field"
                  >
                    <span>{{ field.label }}</span>
                    <span>{{ field.value }}</span>
                  </div>
                  <a
                    v-if="message.content.url"
                    class="hchat-bubble__card-link"
                    :href="String(message.content.url)"
                    target="_blank"
                    rel="noreferrer noopener"
                  >
                    查看详情
                  </a>
                </div>
              </div>

              <div v-else class="hchat-bubble__text">{{ message.content.text }}</div>
            </div>

            <div class="hchat-row__footer">
              <span v-if="index === 0 || shouldShowTimestamp(store.messages.value[index - 1], message)" class="hchat-row__time">
                {{ formatTime(message.createdAt) }}
              </span>
              <button
                v-if="isOwn(message) && message.status === 'sent'"
                type="button"
                class="hchat-link-button"
                @click="store.recallMessage(message.id)"
              >
                撤回
              </button>
              <span
                v-if="isOwn(message) && readMarker && compareId(readMarker, message.id) >= 0"
                class="hchat-row__read"
              >
                已读
              </span>
            </div>
          </div>
        </div>
      </template>

      <div v-if="store.streamingText.value !== null" class="hchat-row">
        <span class="hchat-avatar hchat-avatar--sm" :style="{ backgroundColor: avatarColor('ai') }" aria-hidden="true">
          AI
        </span>
        <div class="hchat-row__body">
          <div class="hchat-row__name">AI 助手</div>
          <div class="hchat-bubble hchat-bubble--streaming">
            {{ store.streamingText.value || '正在思考…' }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
