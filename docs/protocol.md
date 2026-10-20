# 协议文档（REST / WebSocket / SSE / Webhook）

版本：协议 v1。REST 前缀 `/api/v1`，WebSocket / SSE 事件均带 `version: 1`。

- 所有 ID 为**字符串**（雪花 ID 超出 JS 安全整数范围）
- 所有时间为 ISO-8601 UTC 字符串，毫秒精度
- 所有错误响应体固定为：

```json
{ "error": { "code": "not_a_member", "message": "不是该会话成员", "requestId": "0f7c…" } }
```

## 1. 错误码

| code | HTTP | 含义 |
| --- | --- | --- |
| `invalid_request` | 400 | 参数不合法 |
| `unauthorized` | 401 | 未认证 / Token 无效或过期 |
| `app_not_found` | 401 | appId 不存在 |
| `invalid_app_secret` | 401 | appSecret 校验失败 |
| `forbidden` | 403 | 无权限 |
| `not_a_member` | 403 | 不是该会话成员 |
| `message_not_owned` | 403 | 只能撤回自己的消息 |
| `app_disabled` | 403 | 应用被停用 |
| `not_found` / `conversation_not_found` / `message_not_found` | 404 | 资源不存在 |
| `member_exists` | 409 | 成员已存在 |
| `recall_window_expired` | 409 | 超过可撤回时间窗口 |
| `payload_too_large` / `file_too_large` | 413 | 内容/文件超过限制 |
| `unsupported_file_type` | 415 | 文件类型不允许 |
| `rate_limited` | 429 | 触发限流（带 `Retry-After`） |
| `ai_provider_error` | 502 | 模型服务调用失败 |
| `internal_error` | 500 | 服务内部错误 |

每个响应都带 `X-Request-Id`，与错误体里的 `requestId` 一致，便于对日志。

## 2. 鉴权

业务方后端持有 `appId + appSecret`，为具体用户换取短期 Token：

```http
POST /api/v1/auth/token
Content-Type: application/json

{
  "appId": "app_demo",
  "appSecret": "dev-secret-hchat",
  "externalUserId": "buyer_1",
  "displayName": "买家小明",
  "avatarUrl": "https://cdn.example.com/a.png",
  "metadata": { "vip": true }
}
```

响应：

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9…",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "expiresAt": "2026-01-01T01:00:00Z",
  "userId": "370955116188008448",
  "externalUserId": "buyer_1",
  "appId": "app_demo",
  "tenantId": "default",
  "scopes": ["chat", "ai"]
}
```

后续请求带 `Authorization: Bearer <token>`。Token 内包含 `sub`（内部用户 ID）、`appId`、`tenantId`、`externalUserId`、`scopes`、`exp`。

开发模式下（`DEV_MODE=true`）另有 `POST /api/v1/auth/dev-token`，只需 `appId + externalUserId`，**生产必须关闭**。

## 3. REST 接口

### 会话

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/conversations` | 创建会话 |
| GET | `/api/v1/conversations?cursor=&limit=` | 当前用户会话列表（含未读数、最后消息摘要） |
| GET | `/api/v1/conversations/{id}` | 会话详情（含成员与已读位置） |
| POST | `/api/v1/conversations/{id}/members` | 新增成员 |

创建请求：

```json
{
  "type": "c2c",
  "title": "商品咨询",
  "metadata": { "productId": "1001" },
  "dedupeKey": "product_1001:buyer_1",
  "members": [{ "externalUserId": "seller_1", "role": "seller" }],
  "agentId": null
}
```

- `type`：`c2c` | `c2ai` | `c2c_ai_assist`
- `dedupeKey`：同一 app 内唯一，重复调用返回同一会话（适合「商品详情页点联系卖家」）
- `c2ai` 会话不传 `agentId` 时，自动挂载该 app 最早创建的 active 智能体

### 消息

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/conversations/{id}/messages?before=&after=&limit=` | 历史消息 |
| POST | `/api/v1/conversations/{id}/messages` | 发送消息（`clientMsgId` 幂等） |
| POST | `/api/v1/conversations/{id}/read` | 标记已读 |
| POST | `/api/v1/messages/{id}/recall` | 撤回（仅本人，默认 120 秒内） |

游标语义：

- 不传参数：返回最新一页（`items` 按时间正序）
- `before=<messageId>`：向前翻页（更早的消息）
- `after=<messageId>`：断线补拉（更新的消息）
- `nextCursor` 对应下一次请求应使用的游标；`hasMore` 表示还有更多

发送消息：

```json
{ "clientMsgId": "c-1", "type": "text", "content": { "text": "还有货吗？" } }
```

- `clientMsgId` 相同则返回已存在的消息，不会重复插入（同一会话内唯一）
- `type=image` / `file` 需带 `content.url`
- `type=card` 建议带 `content.title`，可选 `subtitle` / `price` / `imageUrl` / `fields` / `url`
- 文本长度上限 `MAX_MESSAGE_LENGTH`（默认 4000）

### 文件

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/files/upload` | `multipart/form-data`，字段名 `file` |

返回 `{ id, url, fileName, mimeType, size }`，`url` 可直接放进消息的 `content.url`。

### AI

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/ai/agents` | 当前 app 的智能体列表 |
| POST | `/api/v1/ai/agents` | 创建智能体 |
| POST | `/api/v1/conversations/{id}/ai/stream` | SSE 流式问答 |

### Webhook

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/webhooks` | 端点列表 |
| POST | `/api/v1/webhooks` | 创建端点（不传 secret 则服务端生成） |

### 运维

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 健康检查（无需鉴权） |
| POST | `/api/v1/admin/archive` | 历史消息导出 JSONL（冷热分离） |

## 4. WebSocket

连接：`ws(s)://host/ws`（可直接带 `?token=`，否则需首帧 `auth`）。

### 客户端 → 服务端

```json
{ "version": 1, "type": "auth", "payload": { "token": "…" } }
{ "version": 1, "type": "message.send", "payload": { "conversationId": "1", "clientMsgId": "c-1", "type": "text", "content": { "text": "hi" } } }
{ "version": 1, "type": "message.read", "payload": { "conversationId": "1", "messageId": "2" } }
{ "version": 1, "type": "typing", "payload": { "conversationId": "1", "isTyping": true } }
{ "version": 1, "type": "ping", "payload": {} }
```

连接后 5 秒内未完成 `auth` 会被断开。

### 服务端 → 客户端

```json
{ "version": 1, "type": "auth.ok", "payload": { "userId": "1", "appId": "app_demo", "tenantId": "default", "externalUserId": "buyer_1", "scopes": ["chat"] } }
{ "version": 1, "type": "message.new", "payload": { "message": { } } }
{ "version": 1, "type": "message.read", "payload": { "conversationId": "1", "userId": "2", "messageId": "3" } }
{ "version": 1, "type": "message.recalled", "payload": { "messageId": "3", "conversationId": "1", "message": { } } }
{ "version": 1, "type": "conversation.updated", "payload": { "conversation": { } } }
{ "version": 1, "type": "presence", "payload": { "userId": "2", "online": true } }
{ "version": 1, "type": "typing", "payload": { "conversationId": "1", "userId": "2", "isTyping": true } }
{ "version": 1, "type": "pong", "payload": { "ts": 1767225600000 } }
{ "version": 1, "type": "error", "payload": { "code": "unauthorized", "message": "请先发送 auth 帧" } }
```

### 心跳与重连

- 客户端每 25s 发 `ping`，10s 内未收到 `pong` 视为断线（`core-sdk` 已内置）
- 断线后按指数退避 + 抖动重连（0.6s → 30s 上限）
- 重连成功后客户端应使用 REST `after=<最后一条消息 ID>` 补拉缺失消息（`core-sdk` 会触发 `reconnected`）

## 5. SSE（AI 流式）

```http
POST /api/v1/conversations/{id}/ai/stream
Authorization: Bearer <token>
Content-Type: application/json

{ "text": "帮我总结这段对话", "agentId": "1000000000000001", "clientMsgId": "c-9" }
```

响应 `text/event-stream`：

```
event: ai.delta
data: {"runId":"1","delta":"好的"}

event: ai.delta
data: {"runId":"1","delta":"，我先看一下…"}

event: ai.done
data: {"runId":"1","message":{...},"usage":{"promptTokens":12,"completionTokens":48}}

event: error
data: {"code":"ai_provider_error","message":"模型服务返回 401"}
```

- 用户消息与 AI 回复都会落库，并通过 WebSocket 广播 `message.new`
- `persistUserMessage=false` 可只取回答不入库

## 6. Webhook

事件：`message.created`、`conversation.created`、`ai.completed`、`notification.offline`

请求头：

```
X-HChat-Signature: sha256=<hex>
X-HChat-Timestamp: <epoch 秒>
X-HChat-Event: message.created
X-HChat-Delivery: <deliveryId>
Content-Type: application/json
```

签名算法：

```
signature = hex(HMAC_SHA256(secret, timestamp + "." + rawRequestBody))
```

校验要点：

1. 用**原始请求体字符串**参与计算（不要先 `JSON.parse` 再 `stringify`）
2. 比对使用常量时间比较
3. 校验 `|now - timestamp| <= 300s` 防重放

Node 侧直接调用 `hchat-server-sdk-node`：

```ts
HChatServerSdk.verifyWebhook(req.headers, rawBody, { secret: process.env.HCHAT_WEBHOOK_SECRET! });
```

投递体：

```json
{
  "id": "370956350340988928",
  "type": "message.created",
  "createdAt": "2026-01-01T00:00:00Z",
  "data": { "conversationId": "1", "message": { } }
}
```

失败重试：默认 5 次，指数退避（1s → 2s → 4s → 8s，单次上限 30s），每次尝试都记入 `webhook_deliveries`。
