# 四种接入方式

前提：业务方后端已用 `appId + appSecret` 换到短期 Token（见 [protocol.md](./protocol.md#2-鉴权)）。**前端永远不接触 appSecret。**

```ts
// 业务方后端（Node 示例）
import { createServerSdk } from 'hchat-server-sdk-node';

const sdk = createServerSdk({
  baseUrl: 'https://chat.example.com',
  appId: process.env.HCHAT_APP_ID!,
  appSecret: process.env.HCHAT_APP_SECRET!,
});

app.get('/api/chat-token', async (req, res) => {
  const { token, expiresAt } = await sdk.issueToken({
    externalUserId: req.user.id,
    displayName: req.user.name,
    avatarUrl: req.user.avatar,
  });
  res.json({ token, expiresAt });
});
```

## 1. 原生 JS（core-sdk）

```bash
pnpm add hchat-core-sdk
```

```ts
import { createChatClient } from 'hchat-core-sdk';

const { token } = await fetch('/api/chat-token').then((r) => r.json());

const client = createChatClient({
  baseUrl: 'https://chat.example.com',
  token,
  appId: 'app_demo',
});

client.on('connection.state', (state) => console.log('连接状态', state));
client.on('message.new', ({ message }) => console.log('新消息', message.content));
client.on('reconnected', async () => {
  // 断线重连后补拉
  const page = await client.messages.list(conversationId, { after: lastMessageId, limit: 100 });
  console.log('补拉', page.items.length);
});

const conversation = await client.conversations.create({
  type: 'c2c',
  metadata: { productId: '1001' },
  dedupeKey: 'product_1001:buyer_1', // 重复调用复用同一会话
  members: [{ externalUserId: 'seller_1', role: 'seller' }],
});

await client.messages.send(conversation.id, { content: { text: '你好，还有货吗？' } });
await client.messages.markRead(conversation.id);
```

Token 过期时调用 `client.setToken(newToken)`，重连会自动使用新 Token；也可传 `onUnauthorized` 回调触发刷新。

## 2. React 组件

```bash
pnpm add hchat-react-chat react react-dom
```

```tsx
import { ChatProvider, ConversationList, ChatWindow, MessageInput, ChatWidget } from 'hchat-react-chat';
import 'hchat-react-chat/styles.css';

// 方式 A：自己拼装（适合做完整页面）
export function Inbox({ token }: { token: string }) {
  return (
    <ChatProvider baseUrl="https://chat.example.com" token={token} appId="app_demo">
      <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', height: 640 }}>
        <ConversationList />
        <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
          <ChatWindow />
          <MessageInput />
        </div>
      </div>
    </ChatProvider>
  );
}

// 方式 B：挂件（自动建会话，适合商品详情页）
export function ProductChat({ token }: { token: string }) {
  return (
    <ChatWidget
      baseUrl="https://chat.example.com"
      appId="app_demo"
      token={token}
      mode="c2c"
      theme="auto"
      height={520}
      conversation={{ productId: '1001', orderId: 'order_9001', sellerId: 'seller_1' }}
      onReady={(conversation) => console.log('会话就绪', conversation.id)}
    />
  );
}
```

主题定制：覆盖 CSS 变量即可（组件本身不锁定设计）。

```css
.hchat-root {
  --hchat-primary: #14b8a6;
  --hchat-radius: 10px;
}
```

`data-theme="light|dark|auto"` 控制明暗模式，默认跟随系统。

## 3. Vue 3 组件

```bash
pnpm add hchat-vue-chat vue
```

```vue
<script setup lang="ts">
import { ChatProvider, ConversationList, ChatWindow, MessageInput, ChatWidget } from 'hchat-vue-chat';
import 'hchat-vue-chat/styles.css';

defineProps<{ token: string }>();
</script>

<template>
  <ChatProvider base-url="https://chat.example.com" app-id="app_demo" :token="token">
    <div class="inbox">
      <ConversationList />
      <div class="main">
        <ChatWindow />
        <MessageInput />
      </div>
    </div>
  </ChatProvider>

  <!-- 或者直接用挂件 -->
  <ChatWidget
    base-url="https://chat.example.com"
    app-id="app_demo"
    :token="token"
    mode="c2c"
    :conversation="{ productId: '1001', sellerId: 'seller_1' }"
    @ready="(c) => console.log('会话就绪', c.id)"
  />
</template>
```

## 4. Web Component

```html
<script type="module" src="https://cdn.example.com/hchat-web-component-chat.js"></script>

<hchat-widget
  base-url="https://chat.example.com"
  app-id="app_demo"
  token="eyJhbGciOi…"
  mode="c2c"
  product-id="1001"
  seller-id="seller_1"
  theme="auto"
  height="560px"
></hchat-widget>

<script>
  const widget = document.querySelector('hchat-widget');
  widget.addEventListener('ready', (event) => console.log('会话', event.detail.id));
  widget.addEventListener('message', (event) => console.log('新消息', event.detail));
  // Token 轮换
  widget.updateToken(newToken);
</script>
```

属性：`base-url` `app-id` `token` `mode` `conversation-id` `product-id` `order-id` `seller-id` `dedupe-key` `agent-id` `title` `theme` `height`

## 5. iframe 嵌入页

独立部署 `apps/embed`（默认 `deploy/docker-compose.yml` 里的 `embed` 服务）。

```html
<iframe
  src="https://embed.example.com/?baseUrl=https%3A%2F%2Fchat.example.com&appId=app_demo&token=eyJ…&mode=c2c&productId=1001&sellerId=seller_1&theme=auto&hostOrigin=https%3A%2F%2Fshop.example.com"
  style="width: 420px; height: 620px; border: 0"
  allow="clipboard-write"
></iframe>
```

Query 参数：`baseUrl` `appId` `token` `mode` `conversationId` `productId` `orderId` `sellerId` `dedupeKey` `theme` `hostOrigin`

### postMessage 协议

宿主 → iframe：

```js
iframe.contentWindow.postMessage({ source: 'hchat-host', type: 'auth.token.update', payload: { token } }, 'https://embed.example.com');
iframe.contentWindow.postMessage({ source: 'hchat-host', type: 'conversation.open', payload: { conversationId: '123' } }, 'https://embed.example.com');
iframe.contentWindow.postMessage({ source: 'hchat-host', type: 'theme.update', payload: { theme: 'dark' } }, 'https://embed.example.com');
```

iframe → 宿主：

```js
window.addEventListener('message', (event) => {
  if (!['https://embed.example.com'].includes(event.origin)) return;   // 必须校验 origin
  const { source, type, payload } = event.data;
  if (source !== 'hchat-embed') return;
  switch (type) {
    case 'ready':        /* payload: { conversationId } */ break;
    case 'unread.count': /* payload: { count, conversationId } */ break;
    case 'message.send': /* payload: { message } */ break;
    case 'error':        /* payload: { message, code } */ break;
  }
});
```

安全要求：

- `hostOrigin` 参数（逗号分隔）用于校验宿主消息来源，为空时只做基础校验
- iframe 页面响应头带 `Content-Security-Policy: frame-ancestors *`，生产建议改为具体域名
- Token 建议通过 postMessage 下发而不是放在 URL 里（避免出现在 Referer / 日志）

## 选择建议

| 场景 | 推荐 |
| --- | --- |
| 已有 React 站内信/客服中心 | `hchat-react-chat` 组合式 |
| 商品详情页「联系卖家」浮窗 | `ChatWidget` / Web Component |
| 第三方站点、无法改构建 | iframe 嵌入页 |
| 小程序 / 非 Web 端 | 直接用 REST + WebSocket（按 `docs/protocol.md`） |
