# hchat-vue-chat

HChat 的 Vue 3 聊天组件，API 与 React 版本对齐。

```bash
npm i hchat-vue-chat hchat-core-sdk
```

```vue
<script setup lang="ts">
import { ChatWidget } from 'hchat-vue-chat';
import 'hchat-vue-chat/styles.css';
</script>

<template>
  <ChatWidget
    base-url="https://chat.example.com"
    app-id="app_demo"
    :token="token"
    mode="c2c"
    :conversation="{ productId: '1001', sellerId: 'seller_1' }"
  />
</template>
```

也可以用组合式函数自己拼：

```ts
import { createChatStore, useChat, chatKey } from 'hchat-vue-chat';

const store = createChatStore({ client }); // 或 { baseUrl, appId, token }
app.provide(chatKey, store);

// 任意子组件内
const chat = useChat();
await chat.sendMessage({ text: '你好' });
```

- 提供 `ChatProvider` / `ConversationList` / `ChatWindow` / `MessageInput` / `ChatWidget`
- 依赖 `vue` ^3.4（peer）
- Vite 构建，同时产出 ESM 与 UMD

接入文档见 [docs/integration.md](https://github.com/hchat-dev/hchat/blob/main/docs/integration.md)。

Apache-2.0
