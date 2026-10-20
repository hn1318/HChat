# 自托管指南

## 1. 一键启动（Docker Compose）

```bash
git clone <repo> && cd hchat
cp .env.example .env            # 按需修改
docker compose -f deploy/docker-compose.yml up --build
```

启动后的地址：

| 服务 | 地址 |
| --- | --- |
| API / WebSocket | http://localhost:8080 |
| 体验页 playground | http://localhost:8000 |
| iframe 嵌入页 embed | http://localhost:8081 |
| MySQL | localhost:3306 |

默认会执行 seed：租户 `default`、应用 `app_demo`（secret `dev-secret-hchat`）、两个内置智能体。
打开 http://localhost:8000，用预置用户（买家小明 / 卖家老王）即可聊起来。

> 演示环境 `DEV_MODE=true` 会开放免密登录接口 `/api/v1/auth/dev-token`。**生产务必设为 `false`。**

可选组件：

```bash
docker compose -f deploy/docker-compose.yml --profile redis up -d   # 多实例广播/在线状态
docker compose -f deploy/docker-compose.yml --profile minio up -d   # S3 兼容对象存储
```

## 2. 本地开发（不用 Docker）

后端支持嵌入式 H2（MySQL 兼容模式），零外部依赖：

```bash
pnpm install
pnpm server:dev          # 等价于 mvn -f server/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev
```

- 数据库文件：`server/.data/hchat.mv.db`
- 上传目录：`server/.data/uploads`
- 健康检查：`curl http://localhost:8080/health`

想连真实 MySQL：

```bash
export DATABASE_URL='jdbc:mysql://localhost:3306/hchat?...'
export DATABASE_USERNAME=hchat DATABASE_PASSWORD=hchat
export FLYWAY_LOCATIONS='classpath:db/migration,classpath:db/seed'
mvn -f server/pom.xml spring-boot:run
```

前端：

```bash
pnpm build                # 先构建 packages（apps 依赖 dist）
pnpm --filter hchat-playground dev
```

## 3. 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PORT` | 8080 | 监听端口 |
| `PUBLIC_BASE_URL` | http://localhost:8080 | 对外地址，用于拼文件 URL |
| `DATABASE_URL` | MySQL 本地 | JDBC URL（`connectionTimeZone=UTC`） |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | hchat | 数据库账号 |
| `DB_POOL_SIZE` | 20 | Hikari 最大连接数 |
| `JWT_SECRET` | 开发默认值 | 令牌签名密钥，生产必须更换（≥32 字节） |
| `JWT_TTL_SECONDS` | 3600 | Token 有效期 |
| `DEFAULT_TENANT_ID` | default | 单租户模式租户 ID |
| `DEV_MODE` | false | 开发模式（开放 dev-token） |
| `WORKER_ID` | 0 | 雪花 ID 机器位，多实例必须不同（0-1023） |
| `CORS_ORIGINS` | localhost:5173 等 | 允许的来源，逗号分隔 |
| `FLYWAY_LOCATIONS` | classpath:db/migration | 追加 `classpath:db/seed` 可写入演示数据 |
| `STORAGE_DRIVER` | local | `local` 或 `s3` |
| `STORAGE_LOCAL_DIR` | ./.data/uploads | 本地存储目录 |
| `S3_ENDPOINT` / `S3_BUCKET` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` | - | S3 / MinIO 配置 |
| `AI_PROVIDER` | mock | `mock`（无需 Key）或 `openai-compatible` |
| `AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL` | OpenAI 官方 | 兼容 DeepSeek、Moonshot、Ollama、vLLM 等 |
| `RATE_LIMIT_ENABLED` | true | 是否限流 |
| `RATE_LIMIT_IP_RPS` / `USER_RPS` / `APP_RPS` | 60 / 30 / 300 | 各自 QPS |
| `MAX_MESSAGE_LENGTH` | 4000 | 单条消息字符上限 |
| `RECALL_WINDOW_SECONDS` | 120 | 撤回时间窗口 |
| `MAX_FILE_BYTES` | 20MB | 上传大小上限 |
| `WEBHOOK_MAX_ATTEMPTS` | 5 | 投递重试次数 |
| `REDIS_ENABLED` / `REDIS_URL` | false | 多实例共享广播 |

完整清单见 `.env.example`。

## 4. 数据库

Flyway 自动迁移，脚本在 `server/src/main/resources/db/migration`。

```bash
mvn -f server/pom.xml flyway:info        # 查看迁移状态
```

关键索引：

- `messages (conversation_id, id)`：游标分页主路径
- `messages (conversation_id, client_msg_id)` 唯一：发送幂等
- `conversation_members (conversation_id, user_id)` 唯一：成员校验
- `conversations (app_id, dedupe_key)` 唯一：会话去重

冷热分离：`POST /api/v1/admin/archive` 会把指定时间前的消息导出成 JSONL 落到存储（默认不删除原数据）。

## 5. 生产建议

- **TLS 终止**放在 Nginx / Ingress，WebSocket 需要 `proxy_read_timeout` 调大（示例 3600s）
- 容器统一 `TZ=UTC`，数据库连接串保持 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`，避免时间列漂移
- 至少 2 个实例时：
  - `WORKER_ID` 各不相同
  - `REDIS_ENABLED=true`，让 `message.new` 广播跨实例
  - 限流目前是进程内令牌桶，多实例下按实例计算，可按需在网关层再加一层
- 文件存储切到 S3/MinIO（`STORAGE_DRIVER=s3`），并让 Nginx/ CDN 直接回源，不要让应用进程转发大文件
- 备份：MySQL 逻辑备份即可；`files` 表与对象存储需要一起备份
- 观测：`/health` 已包含数据库、存储、AI provider、WS 连接数；日志带 `requestId`，建议接入集中式日志

## 6. Kubernetes

`deploy/helm/hchat` 提供了最小可用 Chart（Deployment + Service + ConfigMap + PVC + 可选 Ingress）：

```bash
helm install hchat deploy/helm/hchat \
  --set env.DATABASE_URL='jdbc:mysql://mysql:3306/hchat?...' \
  --set env.JWT_SECRET='your-very-long-random-secret' \
  --set env.CORS_ORIGINS='https://app.example.com'
```

注意：

- `replicaCount > 1` 时必须设置 `REDIS_ENABLED=true` 并给每个副本不同 `WORKER_ID`（可通过 StatefulSet 或环境变量注入实现）
- Chart 暂不包含 MySQL / Redis（生产建议用托管服务）

## 7. 常见问题

**启动报 `Port 53430 was already in use`**
环境里存在 `SERVER__PORT` 之类的变量，Spring 会优先读取。显式指定端口即可：`java -jar hchat-server.jar --server.port=8080`。

**前端请求报 CORS 错误**
把前端地址加入 `CORS_ORIGINS`，或在 `apps.allowed_origins` 里配置（两者取并集）。

**AI 一直返回 Mock 内容**
`AI_PROVIDER=mock` 是默认值，改成 `openai-compatible` 并配置 `AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL`。

**WebSocket 连不上**
检查反向代理是否放行 `Upgrade` / `Connection` 头；Nginx 需要：

```nginx
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
```
