# hchat-server-sdk-node

HChat 的**服务端** SDK（只能在业务方后端跑）：为用户签发短期 Token、校验 Webhook 签名。

```bash
npm i hchat-server-sdk-node
```

```ts
import { createServerSdk } from 'hchat-server-sdk-node';

const sdk = createServerSdk({ baseUrl, appId, appSecret });

// 1. 业务系统登录后，为当前用户换一个短期 Token 给前端
const { token, expiresAt } = await sdk.issueToken({
  externalUserId: 'buyer_1',
  displayName: '买家小明',
});
```

Webhook 验签（注意：**必须用原始请求体**，不能重新 `JSON.stringify`）：

```ts
import express from 'express';
import { HChatServerSdk } from 'hchat-server-sdk-node';

app.post('/hchat/webhook', express.raw({ type: '*/*' }), (req, res) => {
  const event = HChatServerSdk.verifyWebhook(req.headers, req.body.toString('utf8'), {
    secret: process.env.HCHAT_WEBHOOK_SECRET!,
  });
  res.sendStatus(200);
});
```

- 签名算法与服务端一致：`HMAC_SHA256(secret, ${timestamp}.${body})` 的 hex
- 默认 300s 时间戳容差，防重放；失败抛 `HChatServerSdkError`（带 `code` / `status`）
- ⚠️ `appSecret` 绝不能下发到浏览器

接口文档见 [docs/protocol.md](https://github.com/hchat-dev/hchat/blob/main/docs/protocol.md)。

Apache-2.0
