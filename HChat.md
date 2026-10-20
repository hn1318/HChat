
# 项目提示词：开源聊天基础设施 HChat

## 0. 你的角色与目标

你是一名资深全栈架构师兼编码 Agent。请从零构建一个可开源的聊天基础设施项目，暂定名 `HChat`，包作用域 `@HChat-open/*`。

目标：提供一个可自托管的聊天后端服务 + 多形态前端接入 SDK，支持：
1. C2C 聊天：如校书淘中买家与卖家聊天。
2. C2AI 聊天：用户与智能体对话，支持流式输出。
3. 可选 C2C_AI_ASSIST：买卖聊天中 AI 辅助总结/推荐回复，首期可只留接口。
4. 前端接入形态：React 组件、Vue 组件、Web Component、iframe 嵌入页。
5. 后端独立部署，多租户可选，外部依赖可插拔，Docker Compose 一键启动。
6. 开源核心采用 Apache-2.0，Open Core 边界预留，但首期不实现商业版。

你必须先输出实施计划，再按里程碑逐步实现。每个里程碑必须可运行、可测试、有文档。禁止一次性生成大量不可运行代码。

---

## 1. 非目标与约束

首期不做：
- 微服务拆分，先做模块化单体。
- 自研 IM 协议底层，基于 WebSocket + HTTP + SSE。
- 复杂音视频通话。
- 完整商业客服质检、SLA、多地域高可用。
- 强制绑定任何云厂商、模型厂商、前端框架。

必须遵守：
- TypeScript 全栈，严格模式。
- pnpm monorepo + Turborepo。
- 默认 PostgreSQL；Redis 可选，未配置时使用内存实现回退。
- 对象存储默认本地磁盘，可选 S3/MinIO。
- AI Provider 默认 OpenAI 兼容接口，可插件替换。
- 所有外部依赖通过接口抽象：AIProvider、StorageProvider、NotificationProvider、AuthProvider、VectorStore（可选）。
- 前端绝不能持有 `appSecret` 或模型 Key。
- 默认安全：短期 Token、CORS 白名单、限流、消息长度限制、Webhook HMAC 签名、iframe origin 校验。
- 所有数据库查询必须带 `tenantId` 或 `appId` 隔离条件；单租户模式使用 `default`。
- 协议版本化：REST `/api/v1`，WebSocket 事件带 `version`。
- 每个阶段结束输出：变更说明、运行命令、测试结果、未决问题。

---

## 2. 推荐技术栈

后端：
- Java 21，Spring Boot 3.x，Maven。
- Web：Spring MVC + spring-boot-starter-websocket。
- 实时：WebSocket + SSE（AI 流式）。
- ORM：MyBatis-Plus；SQL 写在 Mapper XML 或注解，禁止全表扫描。
- 迁移：Flyway。
- 校验：Jakarta Validation + 自定义错误码。
- 鉴权：Spring Security + JWT 短期 Token。
- 缓存/在线状态/WS 广播：Redis；未配置时用 Caffeine/内存回退。
- 异步：Spring Event + @Async，生产可切 RabbitMQ/Kafka。
- 对象存储：本地磁盘 / MinIO / S3 SDK。
- 测试：JUnit 5 + Testcontainers（MySQL 8 + Redis）。
- 文档：springdoc-openapi，导出 OpenAPI 到 docs/openapi.yaml。

存储：
- MySQL 8 作为主存储。
- 消息表用雪花 ID，游标分页，`(conversation_id, id DESC)` 索引。
- content 用 JSON 字段。
- 会话表冗余最后消息摘要；成员表维护 unread_count。
- 首期单库单表；预留 conversation_id 分片键；可选按月分区。
- 冷热分离：提供归档任务接口，首期实现为按时间导出 JSONL 到本地/对象存储。

前端不变：pnpm monorepo，core-sdk / react-chat / vue-chat / web-component-chat / embed。

仓库结构改为：
hchat-open/
  server-java/            # Spring Boot 多模块或单模块
    src/main/java/...
    src/main/resources/db/migration/
  packages/               # 前端 TS 包，pnpm 管理
  apps/embed/
  apps/playground/
  examples/xiaoshutao-c2c/
  deploy/docker-compose.yml   # server-java + mysql + redis + minio
  
前端：
- `packages/core-sdk`：框架无关，fetch + WebSocket + SSE + 自动重连 + 事件订阅。
- `packages/react-chat`：React 18+，Vite 构建。
- `packages/vue-chat`：Vue 3。
- `packages/web-component-chat`：Lit 或原生 Custom Elements。
- `apps/embed`：iframe 嵌入页，React 或 Vue 均可，但必须独立构建。
- `apps/playground`：本地体验页。

部署：
- Docker Compose：server + postgres + redis+ minio。
- Helm Chart 首期可留目录和 README，不要求完整生产级。

---

## 3. 仓库结构

请创建：

```text
hchat-open/
  apps/
    server/
    embed/
    playground/
    docs/
  packages/
    core-sdk/
    react-chat/
    vue-chat/
    web-component-chat/
    server-sdk-node/
    shared-types/
    ui/
  plugins/
    ai-openai-compatible/
    storage-local/
    storage-s3/
    notification-webhook/
  examples/
    xiaoshutao-c2c/
    ai-agent-demo/
  deploy/
    docker-compose.yml
    helm/
  docs/
    integration.md
    protocol.md
    self-hosting.md
    security.md
    plugin-development.md
  .github/workflows/ci.yml
  turbo.json
  pnpm-workspace.yaml
  package.json
  LICENSE
  README.md
  DECISIONS.md


```
---

## 4. 数据模型

实现以下核心表，字段可按 ORM 习惯调整，但语义必须保留：

1. `tenants`：id, name, status, created_at。
2. `apps`：id, tenant_id, name, app_secret_hash, allowed_origins, config_json, created_at。
3. `external_users`：id, tenant_id, app_id, external_user_id, display_name, avatar_url, metadata_json, created_at。唯一索引 `(app_id, external_user_id)`。
4. `conversations`：id, tenant_id, app_id, type (`c2c`|`c2ai`|`c2c_ai_assist`), title, created_by, metadata_json, last_message_at, created_at, updated_at。
5. `conversation_members`：id, conversation_id, user_id, role (`buyer`|`seller`|`agent`|`member`), joined_at, last_read_message_id, unread_count。唯一索引 `(conversation_id, user_id)`。
6. `messages`：id, tenant_id, app_id, conversation_id, sender_type (`user`|`ai`|`system`), sender_id, type (`text`|`image`|`file`|`card`|`system`), content_json, client_msg_id, status (`sent`|`deleted`|`recalled`), created_at。唯一索引 `(conversation_id, client_msg_id)` 用于幂等。
7. `read_receipts`：conversation_id, user_id, message_id, read_at。
8. `ai_agents`：id, tenant_id, app_id, name, provider, model, system_prompt, config_json, status。
9. `ai_runs`：id, conversation_id, message_id, agent_id, status, usage_json, error, created_at, finished_at。
10. `files`：id, tenant_id, app_id, storage_key, url, mime_type, size, uploader_id, created_at。
11. `webhook_endpoints`：id, tenant_id, app_id, url, secret, events_json, status。
12. `webhook_deliveries`：id, endpoint_id, event, payload_json, status, attempts, last_error, created_at。
13. `audit_logs`：id, tenant_id, app_id, actor_id, action, target_type, target_id, metadata_json, created_at。

要求：
- 所有业务表带 `tenant_id`、`app_id`。
- 单租户模式默认 `tenant_id='default'`。
- 消息内容用 JSONB，便于 text/card/file 扩展。
- 提供迁移脚本和 seed 脚本，seed 包含校书淘示例数据。

---

## 5. 鉴权与 Token

流程：
1. 业务方后端持有 `appId + appSecret`。
2. 业务方后端调用 `POST /api/v1/auth/token`，传入 `appId, appSecret, externalUserId, displayName, metadata`。
3. 服务端校验后签发短期 JWT，默认 1 小时，包含 `sub=internalUserId`、`appId`、`tenantId`、`externalUserId`、`scopes`、`exp`。
4. 前端 SDK 只使用短期 Token 连接 REST/WS/SSE。
5. 提供 `packages/server-sdk-node` 封装签发 Token 和 Webhook 验签。

安全要求：
- `appSecret` 只存哈希。
- Token 必须校验 appId 与用户映射。
- WebSocket 连接后首帧必须 `auth`，超时断开。
- CORS 使用 `apps.allowed_origins`。
- 默认限流：每 IP、每用户、每 app 可配置。

---

## 6. REST API 契约

至少实现：

```text
GET  /health
POST /api/v1/auth/token
POST /api/v1/conversations
GET  /api/v1/conversations
GET  /api/v1/conversations/:id
POST /api/v1/conversations/:id/members
GET  /api/v1/conversations/:id/messages?before=&limit=
POST /api/v1/conversations/:id/messages
POST /api/v1/conversations/:id/read
POST /api/v1/messages/:id/recall
POST /api/v1/files/upload
GET  /api/v1/ai/agents
POST /api/v1/ai/agents
POST /api/v1/conversations/:id/ai/stream
GET  /api/v1/webhooks
POST /api/v1/webhooks
```

要求：
- 请求/响应使用 Zod schema，并导出到 `shared-types`。
- 错误格式统一：`{ error: { code, message, requestId } }`。
- 分页使用 cursor。
- 消息发送支持 `clientMsgId` 幂等。
- 会话列表返回未读数、最后一条消息摘要。
- 文件上传首期本地磁盘，返回可访问 URL。

---

## 7. WebSocket 事件协议

连接：`/ws?token=...` 或连接后首帧 auth。

客户端事件：
```json
{ "version": 1, "type": "auth", "payload": { "token": "..." } }
{ "version": 1, "type": "message.send", "payload": { "conversationId": "...", "clientMsgId": "...", "type": "text", "content": { "text": "hi" } } }
{ "version": 1, "type": "message.read", "payload": { "conversationId": "...", "messageId": "..." } }
{ "version": 1, "type": "typing", "payload": { "conversationId": "...", "isTyping": true } }
```

服务端事件：
```json
{ "version": 1, "type": "auth.ok", "payload": { "userId": "..." } }
{ "version": 1, "type": "message.new", "payload": { "message": { ... } } }
{ "version": 1, "type": "message.read", "payload": { "conversationId": "...", "userId": "...", "messageId": "..." } }
{ "version": 1, "type": "message.recalled", "payload": { "messageId": "..." } }
{ "version": 1, "type": "conversation.updated", "payload": { "conversation": { ... } } }
{ "version": 1, "type": "presence", "payload": { "userId": "...", "online": true } }
{ "version": 1, "type": "error", "payload": { "code": "...", "message": "..." } }
```

要求：
- 支持心跳 ping/pong。
- 支持断线重连和消息补拉。
- 在线状态默认内存实现，配置 Redis 后使用 Redis。
- AI 流式优先 SSE：`POST /api/v1/conversations/:id/ai/stream` 返回 `text/event-stream`，事件 `ai.delta`、`ai.done`、`error`。

---

## 8. Webhook

事件：
- `message.created`
- `conversation.created`
- `ai.completed`
- `notification.offline`

要求：
- HMAC-SHA256 签名，Header：`X-ChatOpen-Signature: sha256=...`。
- 带 `X-ChatOpen-Timestamp` 防重放。
- 失败重试指数退避，记录 `webhook_deliveries`。
- 提供 server-sdk-node 验签函数。

---

## 9. 插件接口

在 `packages/shared-types` 或 `apps/server/src/plugins` 定义：

```ts
export interface AIProvider {
  name: string;
  chat(input: AI Chat Input): Promise<AI Chat Result>;
  stream?(input: AI Chat Input): AsyncIterable<AIStreamChunk>;
}

export interface StorageProvider {
  put(key: string, data: Buffer, mimeType: string): Promise<{ url: string }>;
  get(key: string): Promise<Buffer>;
  delete(key: string): Promise<void>;
}

export interface NotificationProvider {
  send(event: NotificationEvent): Promise<void>;
}

export interface AuthProvider {
  verifyToken(token: string): Promise<AuthContext>;
}
```

首期实现：
- `plugins/ai-openai-compatible`
- `plugins/storage-local`
- `plugins/storage-s3`
- `plugins/notification-webhook`
- Redis/内存队列回退。

---

## 10. 前端 SDK 与组件

### core-sdk
提供：
```ts
createChatClient({ baseUrl, token, appId, tenantId? })
client.conversations.list()
client.conversations.create()
client.messages.list()
client.messages.send()
client.messages.markRead()
client.connect()
client.on('message.new', handler)
client.on('ai.delta', handler)
client.disconnect()
```

要求：
- 自动重连、心跳、事件订阅、错误归一化。
- 不依赖 React/Vue。
- 导出 TypeScript 类型。

### react-chat
导出：
```tsx
<ChatProvider client={client}>
  <ConversationList />
  <ChatWindow conversationId="..." />
  <MessageInput />
  <ChatWidget appId token mode="c2c" conversation={{ productId, orderId, sellerId }} />
</ChatProvider>
```

要求：
- 支持主题变量、暗色模式、移动端。
- 支持文本、图片、文件、卡片消息。
- 未读红点、已读、撤回、时间分组。
- 不绑定后端域名，`baseUrl` 可配置。

### vue-chat / web-component-chat
- Vue 组件 API 与 React 对齐。
- Web Component：`<chat-open-widget base-url app-id token mode>`。
- iframe 嵌入页 `apps/embed`：支持 query `baseUrl, appId, token, mode, conversationId, productId, orderId`。
- iframe 与宿主 postMessage 协议：
  - 宿主 -> iframe：`auth.token.update`、`conversation.open`、`theme.update`。
  - iframe -> 宿主：`ready`、`unread.count`、`navigate`、`message.send`。
  - 必须校验 origin。

---

## 11. 校书淘示例

在 `examples/xiaoshutao-c2c` 实现：
- 模拟买家、卖家登录。
- 商品详情页点击“联系卖家”创建 c2c 会话。
- 聊天中发送商品卡片、订单卡片。
- AI 助手入口：总结聊天、推荐回复，首期可 mock 或接 OpenAI 兼容接口。
- 提供 README：如何启动 server、seed、前端示例。

---

## 12. 部署与配置

提供 `deploy/docker-compose.yml`：
- `server`
- `postgres`
- `redis` 可选 profile
- `minio` 可选 profile
- `embed` 构建产物可由 server 静态托管或独立 nginx

环境变量至少：
```text
PORT
DATABASE_URL
REDIS_URL
JWT_SECRET
DEFAULT_TENANT_ID
CORS_ORIGINS
STORAGE_DRIVER=local|s3
STORAGE_LOCAL_DIR
S3_ENDPOINT
S3_BUCKET
S3_ACCESS_KEY
S3_SECRET_KEY
AI_PROVIDER=openai-compatible
AI_BASE_URL
AI_API_KEY
AI_MODEL
RATE_LIMIT_ENABLED
```

要求：
- `docker compose up` 后能访问 playground 并完成一次聊天。
- 提供 `.env.example`。
- 提供 seed 命令。

---

## 13. 测试与 CI

必须包含：
- 单元测试：Token、权限、消息幂等、Webhook 签名、插件接口。
- 集成测试：创建会话、发消息、拉历史、已读、撤回、WS 推送。
- E2E 冒烟：playground 或 API 脚本完成买家卖家聊天。
- CI：install、lint、typecheck、test、build。
- 每个里程碑必须通过 `pnpm lint && pnpm typecheck && pnpm test && pnpm build`。

---

## 14. 文档

至少：
- `README.md`：项目定位、5 分钟启动、架构图。
- `docs/self-hosting.md`：Docker Compose、环境变量、生产建议。
- `docs/integration.md`：React/Vue/Web Component/iframe 四种接入。
- `docs/protocol.md`：REST、WS、SSE、Webhook、错误码。
- `docs/security.md`：Token、Secret、CORS、限流、iframe origin。
- `docs/plugin-development.md`：如何实现 AI/Storage/Notification 插件。
- `DECISIONS.md`：记录技术选型和未决问题。

---

## 15. 里程碑与交付顺序

### P0 项目骨架
- monorepo、CI、lint、typecheck、Docker Compose、健康检查。
- 交付：`pnpm dev` 可启动，`/health` 可用。

### P1 核心聊天后端
- 数据模型、迁移、seed、Token、会话、消息、已读、撤回、WS。
- 交付：API 集成测试通过，脚本可完成买家卖家聊天。

### P2 前端 SDK 与 React 组件
- core-sdk、react-chat、playground。
- 交付：playground 可登录、建会话、实时聊天。

### P3 AI Agent
- AI Provider 插件、ai_agents、SSE 流式、AI 消息落库。
- 交付：C2AI 会话可流式回答，ai-agent-demo 可运行。

### P4 多形态接入与开源化
- Vue、Web Component、iframe embed、server-sdk-node、Webhook。
- 交付：四种接入方式文档和示例可运行。

### P5 校书淘示例与发布
- xiaoshutao-c2c 示例、完整文档、安全说明、LICENSE、README。
- 交付：`docker compose up` 后按 README 完成校书淘示例聊天。

---

## 16. 验收标准

最终必须满足：
1. `docker compose up` 一键启动，playground 可完成 C2C 聊天。
2. 校书淘示例可创建商品会话、发消息、发卡片、AI 总结入口可用。
3. React/Vue/Web Component/iframe 四种接入均有可运行示例。
4. REST/WS/SSE/Webhook 协议文档完整。
5. Token 不暴露 appSecret，前端不持有模型 Key。
6. 多租户隔离测试通过。
7. Webhook 签名和重试验证通过。
8. CI 全绿。
9. Apache-2.0 LICENSE 和 README 完整。
10. 未实现项记录在 `DECISIONS.md`，不得静默缺失。

---

## 17. 执行方式

请按以下步骤响应：
1. 先输出实施计划、目录结构、技术选型确认、风险点。
2. 从 P0 开始实现，每完成一个里程碑暂停，输出变更说明、运行命令、测试结果。
3. 遇到不确定的选择，写入 `DECISIONS.md` 并选择最简可运行方案。
4. 不要一次性生成全部代码；优先保证每个阶段可运行、可测试、可文档化。
5. 所有代码使用 TypeScript 严格模式，公开 API 必须有类型和最小注释。
6. 每阶段结束给出下一步建议。
```

使用建议：把上面的提示词放进仓库根目录，然后让 Agent 从 **P0** 开始执行；每个里程碑验收后再进入下一阶段。这样能避免 Agent 一次性生成大量不可运行代码，也方便你逐步检查架构是否符合预期。