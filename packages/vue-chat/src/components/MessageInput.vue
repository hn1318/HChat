<script setup lang="ts">
import { computed, ref } from 'vue';
import { useChat } from '../store';

const props = withDefaults(
  defineProps<{
    placeholder?: string;
    disabled?: boolean;
    aiMode?: boolean;
    maxLength?: number;
    allowFile?: boolean;
  }>(),
  { placeholder: '输入消息，Enter 发送', disabled: false, maxLength: 4000, allowFile: true },
);

const store = useChat();
const text = ref('');
const busy = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);
let typingTimer: ReturnType<typeof setTimeout> | null = null;

const isAi = computed(() => props.aiMode ?? store.activeConversation.value?.type === 'c2ai');
const offline = computed(() => store.connectionState.value !== 'authenticated');
const cannotSend = computed(
  () =>
    props.disabled ||
    !store.activeConversation.value ||
    offline.value ||
    busy.value ||
    store.streamingText.value !== null,
);

function triggerTyping() {
  store.setTyping(true);
  if (typingTimer) clearTimeout(typingTimer);
  typingTimer = setTimeout(() => store.setTyping(false), 1500);
}

async function submit() {
  const value = text.value.trim();
  if (!value || cannotSend.value) return;
  busy.value = true;
  text.value = '';
  store.setTyping(false);
  try {
    if (isAi.value) {
      await store.sendAiMessage(value);
    } else {
      await store.sendMessage({ text: value });
    }
  } finally {
    busy.value = false;
  }
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  busy.value = true;
  try {
    await store.sendMessage({ file });
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="hchat-input">
    <template v-if="props.allowFile">
      <button
        type="button"
        class="hchat-input__icon"
        title="发送图片或文件"
        aria-label="发送图片或文件"
        :disabled="cannotSend"
        @click="fileInput?.click()"
      >
        +
      </button>
      <input ref="fileInput" type="file" hidden @change="onFileChange" />
    </template>

    <textarea
      v-model="text"
      class="hchat-input__textarea"
      rows="1"
      :maxlength="props.maxLength"
      :placeholder="offline ? '连接已断开，正在重连…' : props.placeholder"
      :disabled="cannotSend"
      @input="triggerTyping"
      @keydown.enter.exact.prevent="submit"
    />

    <button
      type="button"
      class="hchat-input__send"
      :disabled="cannotSend || text.trim().length === 0"
      @click="submit"
    >
      {{ store.streamingText.value !== null ? '生成中' : '发送' }}
    </button>
  </div>
</template>
