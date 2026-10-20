# HChat

> 可自托管的聊天基础设施：C2C 聊天、C2AI 智能体对话、以及多形态前端接入。
> 后端 Spring Boot + MySQL，前端框架无关 SDK + React / Vue / Web Component / iframe 四套组件。

[![CI](https://img.shields.io/badge/CI-server%20%2B%20web-brightgreen)](.github/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](server/pom.xml)
[![Node](https://img.shields.io/badge/Node-%E2%89%A520-green)](package.json)

## 它解决什么问题

业务里需要「买家 ↔ 卖家聊天」或「用户 ↔ 智能体对话」时，通常有两条路：接第三方 IM SaaS（数据在别人那、定制受限），或者自己从头写（长连接、未读、已读、幂等、多端同步、AI 流式……全是坑）。

HChat 把这些做成一套可自托管的基础设施：

- **消息可靠**：雪花 ID + 游标分页、`clientMsgId` 幂等、未读数增量维护、已读回执、撤回
- **实时**：WebSocket 双向通道，心跳 + 指数退避重连 + 断线补拉
- **AI 原生**：C2AI 会话、SSE 流式输出、对话上下文自动带入、`ai_runs` 记录用量
- **接入简单**：四行代码嵌入一个聊天窗；四种形态覆盖 React / Vue / 原生 / 第三方站点
- **可自托管**：`docker compose up` 一条命令；MySQL 主存储，Redis / S3 可选，都不配也能跑
- **可扩展**：AI / 存储 / 通知 / 广播全部接口化，换实现不改业务代码

## 架构

```text
                      ┌──────────────────────────────────────────┐
   业务方后端          │              HChat Server                │
   appId+appSecret ──► │  /api/v1/auth/token  (签发短期 JWT)       │
                      │                                          │
   浏览器              │  REST     会话 / 消息 / 已读 / 撤回 / 文件 │
   短期 JWT ─────────► │  WS       /ws  实时收发 + 在线 + 输入中    │──► MySQL 8 (Flyway)
                      │  SSE      /ai/stream  流式回答             │──► Redis  (可选)
                      │                                          │──► S3/MinIO (可选)
                      │  插件：AIProvider / StorageProvider /     │──► Webhook (HMAC)
                      │        NotificationProvider / WsBroadcaster│
                      └──────────────────────────────────────────┘
                                   ▲            ▲
                     core-sdk ─────┘            └───── embed (iframe + postMessage)
                     react-chat / vue-chat / web-component-chat
```

关键设计：

- **前端不持有任何密钥**：`appSecret` 只在业务方后端，模型 Key 只在服务端
- **多租户隔离**：所有查询强制带 `tenant_id + app_id`，跨租户访问返回 404
- **协议版本化**：REST `/api/v1`，WS 事件带 `version`

## 5 分钟启动

```bash
git clone <repo> && cd hchat
cp .env.example .env
docker compose -f deploy/docker-compose.yml up --build
```

| 地址 | 说明 |
| --- | --- |
| http://localhost:8000 | 体验页 playground（选「买家小明」，点「+ 商品会话」） |
| http://localhost:8080 | API / WebSocket |
| http://localhost:8081 | iframe 嵌入页 |

想验证双端实时：点右侧「开第二身份」按钮，会用「卖家老王」新开一个标签页，两边互发消息即刻到达。

> 演示环境开放了免密登录接口（`DEV_MODE=true`），上线前请看 [docs/security.md](docs/security.md#10-上线检查清单)。

## 本地开发

```bash
pnpm install

# 后端：默认嵌入式 H2，无需 MySQL / Redis，自动执行迁移 + 演示数据
pnpm server:dev            # http://localhost:8080/health

# 前端：先构建 packages（apps 依赖 dist），再起 playground
pnpm build
pnpm --filter hchat-playground dev   # http://localhost:5173
```

需要真实 MySQL：

```bash
export DATABASE_URL='jdbc:mysql://localhost:3306/hchat?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
export DATABASE_USERNAME=hchat DATABASE_PASSWORD=hchat
mvn -f server/pom.xml spring-boot:run
```

## 目录结构

```text
server/                 Java 21 + Spring Boot（REST / WS / SSE / Webhook / 插件）
  src/main/java/com/hchat/server/
    api/ auth/ service/ ws/ mapper/ domain/ plugin/ config/ common/
  src/main/resources/db/migration/     Flyway 脚本（含 seed）
packages/
  shared-types/         协议类型 + Zod 校验（前后端唯一契约）
  core-sdk/             框架无关客户端：REST + WS + SSE + 重连
  react-chat/           React 组件（Provider / List / Window / Input / Widget）
  vue-chat/             Vue 3 组件（API 对齐）
  web-component-chat/   <hchat-widget> 自定义元素
  server-sdk-node/      业务方后端 SDK：签发 Token + Webhook 验签
apps/
  playground/           本地体验页
  embed/                iframe 嵌入页（postMessage 协议）
  docs/                 文档站（渲染 docs/*.md）
examples/
  ai-agent-demo/        命令行演示：签发 Token → C2AI → SSE 流式输出
deploy/
  docker-compose.yml    server + mysql + playground + embed（+ redis/minio profile）
  Dockerfile.web        前端静态站镜像
  helm/hchat/           Kubernetes Chart（最小可用）
scripts/
  smoke.mjs             接口级 E2E（C2C + WS + SSE）
  ui-check.mjs          Chrome CDP 真实浏览器 E2E（双身份实时聊天）
docs/                   protocol / integration / self-hosting / security / plugin-development
DECISIONS.md            选型理由 + 未实现项
```

## 接入

四种形态的完整示例见 [docs/integration.md](docs/integration.md)。

```tsx
// React：商品详情页挂一个聊天窗
<ChatWidget
  baseUrl="https://chat.example.com"
  appId="app_demo"
  token={token}
  mode="c2c"
  conversation={{ productId: '1001', sellerId: 'seller_1' }}
/>
```

```html
<!-- 任意页面 -->
<hchat-widget base-url="https://chat.example.com" app-id="app_demo" token="…" mode="c2c"></hchat-widget>
```

服务端签发 Token（业务方后端）：

```ts
const sdk = createServerSdk({ baseUrl, appId, appSecret });
const { token } = await sdk.issueToken({ externalUserId: 'buyer_1', displayName: '买家小明' });
```

## 测试

```bash
# 后端：23 个测试（单元 + H2 集成 + WebSocket 实时）
mvn -f server/pom.xml test

# 后端：额外跑 Testcontainers（需要 Docker）
mvn -f server/pom.xml verify -Pit

# 前端：lint / 类型 / 单测 / 构建
pnpm lint && pnpm typecheck && pnpm test && pnpm build

# 接口级 E2E（需要 server 以 dev profile 运行）
node scripts/smoke.mjs

# 浏览器级 E2E（需要 server + playground + Chrome --remote-debugging-port=9222）
node scripts/ui-check.mjs
```

覆盖情况：

- Token 签发/过期/篡改、appSecret 哈希、雪花 ID、限流
- 会话去重、消息幂等、未读数、已读、撤回窗口、多租户隔离、错误码格式
- WebSocket 首帧鉴权、`message.new` 广播、已读回执、心跳
- Webhook HMAC 签名（含跨语言参考向量）、重试退避、事件订阅过滤
- 前端 SDK 的 URL 拼接、错误归一化、SSE 跨分片解析

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/protocol.md](docs/protocol.md) | REST / WS / SSE / Webhook 契约与错误码 |
| [docs/integration.md](docs/integration.md) | React / Vue / Web Component / iframe 四种接入 |
| [docs/self-hosting.md](docs/self-hosting.md) | Docker Compose、环境变量、生产建议、FAQ |
| [docs/security.md](docs/security.md) | Token、密钥、CORS、限流、iframe origin、上线清单 |
| [docs/plugin-development.md](docs/plugin-development.md) | 如何实现 AI / 存储 / 通知插件 |
| [DECISIONS.md](DECISIONS.md) | 技术选型理由与未实现项 |

## 当前状态

- ✅ P0 骨架 / CI / Compose / 健康检查
- ✅ P1 核心聊天后端（迁移、鉴权、会话、消息、已读、撤回、WS）
- ✅ P2 core-sdk + react-chat + playground
- ✅ P3 AI Agent + SSE 流式 + ai-agent-demo
- ✅ P4 Vue / Web Component / iframe / server-sdk-node / Webhook / 文档
- ⏭️ P5 校书淘示例与发布 —— 本轮按要求跳过（详见 `DECISIONS.md`）

## License

Apache-2.0 © HChat contributors
