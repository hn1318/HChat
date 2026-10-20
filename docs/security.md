# 安全模型

## 1. 密钥与 Token

| 凭据 | 存放位置 | 生命周期 |
| --- | --- | --- |
| `appId` / `appSecret` | **只在业务方后端** | 长期，可轮换 |
| 短期 JWT | 前端内存 / sessionStorage | 默认 1 小时 |
| 模型 API Key | **只在服务端环境变量** | 长期 |

规则：

- `appSecret` 只存 SHA-256 哈希（`apps.app_secret_hash`），入库前不可逆；比对使用 `MessageDigest.isEqual` 常量时间比较。
- 要求业务方使用高熵随机 secret（`Secrets.randomSecret()` 生成 32 字节 hex）。低熵 secret 不受 bcrypt 保护，务必自查。
- 前端拿不到 `appSecret`，也拿不到模型 Key；所有模型调用只发生在服务端。
- JWT 使用 HS256，签名密钥由 `JWT_SECRET` 经 SHA-256 派生为 32 字节，任意长度配置都满足 HS256 强度要求。
- Token 载荷含 `sub`（内部用户 ID）、`appId`、`tenantId`、`externalUserId`、`scopes`、`exp`、`jti`；校验时强制 `issuer` 匹配。
- 前端收到 401 应重新向后端换 Token，而不是自行续签。

## 2. 多租户隔离

- 所有业务表都带 `tenant_id` + `app_id`；**所有查询都强制带上这两个条件**，由 `ChatPrincipal` 提供，不接受请求体传入。
- 跨 app 访问统一返回 `404 conversation_not_found`，不泄露资源是否存在。
- 单元/集成测试覆盖：不同 app 的用户无法读写对方会话（`ChatApiIntegrationTest#otherTenantCannotAccessConversation`）。
- 跨 app 引用智能体同样被拒绝（`AI_AGENT_NOT_FOUND`）。

## 3. CORS 与 Origin

- 允许来源 = `CORS_ORIGINS` 环境变量 ∪ 所有 `apps.allowed_origins`（缓存 60s，避免预检打库）。
- WebSocket 握手使用同一份来源白名单（`setAllowedOriginPatterns`）。
- 预检请求由 Spring Security 的 `CorsFilter` 直接响应，不进入业务链路。

## 4. 限流

默认开启，三维令牌桶：

- 每 IP（默认 60 rps，突发 = rps × 2）
- 每用户（30 rps）
- 每 app（300 rps）

超限返回 `429` + `Retry-After: 1` + 统一错误体。IP 取 `X-Forwarded-For` 第一跳（需在可信代理后使用）。

调整：`RATE_LIMIT_ENABLED`、`RATE_LIMIT_IP_RPS`、`RATE_LIMIT_USER_RPS`、`RATE_LIMIT_APP_RPS`、`RATE_LIMIT_BURST`。

> 当前实现为进程内计数；多实例部署时按实例独立计算，必要时在网关层再叠加全局限流。

## 5. 输入校验与资源限制

- 请求体走 Jakarta Validation（长度、枚举、必填）。
- 消息文本长度上限 `MAX_MESSAGE_LENGTH`（默认 4000 字符），超限 `payload_too_large`。
- 上传白名单：`image/*`、`audio/*`、`video/*`、`text/plain`、PDF、ZIP、JSON、Office 文档；单文件上限 `MAX_FILE_BYTES`（默认 20MB）。
- 上传文件名做路径分隔符与控制字符清洗；存储 key 由服务端生成（`tenant/app/yyyy/MM/uuid.ext`），本地存储 Provider 还会校验解析后路径必须落在根目录内（防目录穿越）。
- 消息 `content` 最多 50 个字段，避免超大 JSON。

## 6. WebSocket

- 连接后 5 秒内必须完成 `auth`，否则以 `POLICY_VIOLATION` 关闭。
- 帧大小上限 64KB；会话空闲 5 分钟断开。
- 每条业务帧都会重新校验成员关系，不信任客户端传入的会话身份。
- 心跳：客户端 25s 发 `ping`，10s 无 `pong` 由客户端主动重连。

## 7. iframe 嵌入

- `hostOrigin` 参数用于校验宿主消息来源；未配置时退化为只校验 `source` 标记（**生产必须配置**）。
- iframe → 宿主消息使用记录到的真实 `event.origin` 作为 `targetOrigin`，不用 `*`。
- 建议用 postMessage 下发 Token，避免 Token 出现在 URL（会进 Referer、代理日志、浏览器历史）。
- 部署时把 `frame-ancestors` 收紧到业务域名（`deploy/nginx.conf` 默认 `*`，仅为演示）。

## 8. Webhook

- 签名：`HMAC_SHA256(secret, timestamp + "." + rawBody)`。
- 防重放：时间戳窗口默认 300 秒。
- 每次投递记录 `webhook_deliveries`（状态、次数、最后错误），失败指数退避重试。
- 接收方必须用**原始请求体**验签；先解析再序列化会导致验签失败。

## 9. 开发模式开关

`DEV_MODE=true` 会开放：

- `POST /api/v1/auth/dev-token`（免密签发）
- `/health` 里返回 `devMode: true`

生产环境必须 `DEV_MODE=false`，并确认 `GET /health` 里 `devMode` 为 `false`。

## 10. 上线检查清单

- [ ] `JWT_SECRET` 已替换为随机长字符串，且未提交到仓库
- [ ] `DEV_MODE=false`
- [ ] `CORS_ORIGINS` 与 `apps.allowed_origins` 只包含真实业务域名
- [ ] `app_demo` / `dev-secret-hchat` 已删除或轮换
- [ ] 数据库账号最小权限（仅本库 DML/DDL）
- [ ] 上传目录或对象存储不对外公开列目录
- [ ] 反向代理开启 TLS，WebSocket 正确升级
- [ ] 限流阈值按实际流量调整
- [ ] 已配置日志采集与 `/health` 探针
