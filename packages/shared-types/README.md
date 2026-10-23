# hchat-shared-types

HChat 的**协议契约**包：REST / WebSocket / SSE 的类型定义 + Zod 运行时校验。

服务端与前端都以它为准，改协议先改这里。

```bash
npm i hchat-shared-types
```

```ts
import { parseWsFrame, ServerEventType, conversationSchema } from 'hchat-shared-types';

const frame = parseWsFrame(raw); // 非法帧返回 null，不抛异常
if (frame?.type === ServerEventType.messageNew) {
  // …
}
```

包含：

- 数据模型：`Conversation`、`Message`、`ConversationMember`、`Page<T>`、`ChatAgent`、`TokenResponse`
- 常量：`API_PREFIX`、`WS_PROTOCOL_VERSION`、`ServerEventType`、`ClientEventType`
- Zod schema：`conversationSchema`、`messageSchema`、`apiErrorSchema`、`wsFrameSchema`
- 工具：`parseWsFrame(raw)`

> 所有 ID 都是字符串（服务端雪花 ID 超出 JS 安全整数范围），时间都是 ISO-8601 UTC 字符串。

协议全文见 [docs/protocol.md](https://github.com/hchat-dev/hchat/blob/main/docs/protocol.md)。

Apache-2.0
