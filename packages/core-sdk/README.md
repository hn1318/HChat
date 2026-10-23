# hchat-core-sdk

HChat 的框架无关客户端 SDK：REST + WebSocket + SSE，自带重连与事件订阅。React / Vue / Web Component 三套组件都建在它上面。

```bash
npm i hchat-core-sdk
```

```ts
import { createChatClient } from 'hchat-core-sdk';

const client = createChatClient({ baseUrl: 'https://chat.example.com', token });

const off = client.on('message.new', ({ message }) => console.log(message.content.text));

await client.connect?.();                      // 默认 autoConnect，无需手动调用
const page = await client.conversations.list();
await client.messages.send(conversationId, { content: { text: '你好' } });

await client.ai.stream({
  conversationId,
  text: '帮我推荐一台笔记本',
  onDelta: (delta) => process.stdout.write(delta),
});
```

能力：

- REST 封装：`conversations.*` / `messages.*` / `files.upload` / `ai.*`，错误统一归一化为 `ChatError`（带 `code` / `requestId`）
- WebSocket：`auth` 首帧、25s 心跳、断线指数退避（0.6s → 30s）、`connection.state` 事件
- SSE：POST + `fetch` 流式读取（携带 Bearer Token），处理跨分片切包
- 事件：`message.new` / `message.read` / `message.recalled` / `typing` / `presence` / `ai.delta` / `ai.done` / `error`

接入文档见 [docs/integration.md](https://github.com/hchat-dev/hchat/blob/main/docs/integration.md)。

Apache-2.0
