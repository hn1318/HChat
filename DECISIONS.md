# 技术决策记录（DECISIONS）

记录选型理由、偏离原始需求的地方、以及**尚未实现**的能力。原则：不静默缺失。

## 已定决策

### D1 后端用 Java 21 + Spring Boot 3.5，而不是 TypeScript 全栈

原始需求同时存在「TypeScript 全栈」与「Java 21 + Spring Boot + MyBatis-Plus + Flyway + MySQL」两段描述。
后者给出了更具体的落地方案，且 Java 生态在长连接、事务、迁移工具上更成熟，故采用：

- Java 21 / Spring Boot 3.5.9 / Maven
- MyBatis-Plus 做 CRUD，复杂查询用注解 SQL（`ConversationMapper#listForUser`）
- Flyway 管理 schema，MySQL 8 为主存储
- 前端仍保持 TypeScript 严格模式（`packages/*`、`apps/*`）

对「TypeScript 全栈」的偏离：服务端不是 TS。若需要，可另起 NestJS 实现同一套协议，`docs/protocol.md` 与 `shared-types` 即契约。

### D2 仓库布局：服务端在 `server/`，不在 `apps/`

需求里 `apps/server` 与 `server-java/` 两种写法并存。Java 模块不属于 pnpm workspace，放在 `apps/` 会被 turbo 误判，故放根目录 `server/`。前端包统一在 `packages/`、`apps/`。

### D3 ID 与时间

- 雪花 ID（41 位时间戳 + 10 位 worker + 12 位序列），workerId 来自 `WORKER_ID`
- **对外一律用字符串**，避免 JS `Number.MAX_SAFE_INTEGER` 精度丢失；服务端 DTO 直接定义 `String` 字段，不做全局 Long→String 序列化（避免误伤其它数值）
- 时间列统一 `TIMESTAMP(3)`，应用侧 `Instant` 且截断到毫秒，保证「写入返回值」与「再次读取」完全一致

### D4 JSON 列用 `TEXT` 而不是 MySQL `JSON`

需求写的是 JSON/JSONB。选择 `TEXT` + 应用层序列化的原因：

- CI 与本地测试需要 H2（MySQL 兼容模式），`JSON` 类型在 H2 上的写入语义与 MySQL 不一致
- 内容结构由 `shared-types` 与 Zod schema 约束，DB 侧校验收益有限
- 需要按内容检索时再加生成列 + 索引，不影响现有写入路径

### D5 消息幂等

`messages (conversation_id, client_msg_id)` 唯一索引 + 发送前查询：
重复的 `clientMsgId` 直接返回已存在消息，不重复插入、不重复广播、不重复触发 Webhook。

### D6 未读数维护方式

`conversation_members.unread_count` 增量维护（发送时除发送者外 +1，已读时清零），避免每次列表都做 count 聚合。
代价是并发下的最终一致：极端并发可能短暂偏差，下一次已读会归零。

### D7 已读回执

`read_receipts` 每个 `(conversation_id, user_id)` 只保留最新一条（更新而非追加），
历史轨迹交给 `audit_logs` / 数据仓库，避免回执表无限膨胀。

### D8 没有外键

IM 场景写入频繁、跨表删除少，全部表不建外键，一致性由应用层保证；
**所有查询强制带 `tenant_id + app_id`** 作为隔离手段（并有集成测试覆盖）。

### D9 SSE 用 POST + fetch 流式读取，而不是 EventSource

`EventSource` 不支持自定义 Header 和 POST 请求体，无法携带 Bearer Token 与提问内容。
`core-sdk` 用 `fetch` + `ReadableStream` 自行解析 `text/event-stream`（含跨分片切包处理）。

### D10 前端状态管理不引入库

`react-chat` 用 Context + useReducer 级别的自管理，`vue-chat` 用组合式函数，避免把 Redux/Pinia 强加给使用方。
两端共享同一套 CSS 变量与类名，视觉一致。

### D11 `DEV_MODE` 与 `/auth/dev-token`

为了让 `docker compose up` 后 playground 能直接体验，提供免密签发接口。
默认关闭；compose 与 dev profile 显式打开，并在 `/health` 暴露 `devMode` 便于自查。
生产检查清单见 `docs/security.md#10-上线检查清单`。

### D12 测试策略

- 默认 `mvn test` 跑 H2（MySQL 兼容模式）上的完整集成测试，**无需 Docker**
- `mvn verify -Pit` 额外跑 Testcontainers（MySQL 8.4）验证迁移与真实方言
- 前端 `vitest` 覆盖 SDK 的 URL 拼接、错误归一化、SSE 解析、WS 帧处理、Webhook 验签
- 另有 `scripts/smoke.mjs`（接口级 E2E）与 `scripts/ui-check.mjs`（Chrome CDP 真实浏览器 E2E，双身份实时聊天）

### D13 ESLint 不检查 `.vue`

引入 `eslint-plugin-vue` 会增加一层配置，`.vue` 文件已由 `vue-tsc` 做类型检查，故 ESLint 忽略它们。
若团队强依赖 ESLint 规则，可后续补上。

### D14 Redis 可选

`REDIS_ENABLED=false` 时全部走进程内实现（在线状态、WS 广播、限流）。
开启后 `WsBroadcaster` 换成 Pub/Sub 版本，在线状态落 Redis 键（TTL 90s 心跳续期）。
`REDIS_URL` 支持 `redis://[:password@]host:port[/db]`，解析在 `RedisConfig#parseUrl`。

## 未实现 / 已知限制

| 项 | 现状 | 建议 |
| --- | --- | --- |
| P5 校书淘示例（`examples/xiaoshutao-c2c`） | **按本轮要求跳过** | 目录未创建；如需，按 `docs/integration.md` 的 `ChatWidget` 示例即可复刻 |
| 发布/打包到 npm 与 Maven Central | 未做 | 包已配好 `exports` / `files`，补 CI 发布流水线即可 |
| 限流的分布式一致性 | 进程内令牌桶 | 多实例时在网关层加一层全局限流，或改为 Redis 计数 |
| 消息全文检索 | 未做 | 引入 ES / MySQL ngram 索引，走 outbox 同步 |
| 会话级权限（仅创建者可加成员等） | 任何成员都能加成员 | 需要更细的 RBAC 时在 `ConversationService#addMember` 加校验 |
| 消息编辑 / 多端已读明细 | 未做 | 复用 `message.recalled` 的模式扩展事件 |
| 附件访问鉴权 | 本地存储下 `/files/**` 公开可读 | 生产走 S3 预签名 URL 或加签名 token |
| 前端错误上报 / 埋点 | 未做 | 在 `client.on('error')` 里接 Sentry |
| 审计日志覆盖面 | 只覆盖会话创建、撤回、Webhook、AI 创建 | 按需在 Service 层补 `audit.log(...)` |
| `apps/docs` 文档站 | 极简静态站（Markdown 渲染） | 需要搜索/版本化时换 VitePress |
| Helm Chart | 最小可用（不含 MySQL/Redis、无 HPA） | 生产按需补全 |
| MySQL `TIMESTAMP` 2038 上限 | 使用 `TIMESTAMP(3)` | 如需更长跨度改成 `DATETIME(3)`（H2 兼容性需同步验证） |

## 回滚/演进提示

- 协议只增不改：新增事件保留 `version: 1`，破坏性变更走 `version: 2` 并双写一段时间
- `shared-types` 是前后端唯一契约来源，改协议先改这里再改两端实现
- 数据迁移只前进不后退：Flyway `validate-on-migrate=true`，禁止修改已发布脚本
