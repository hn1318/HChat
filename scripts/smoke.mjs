#!/usr/bin/env node
/**
 * HChat 端到端冒烟脚本：不依赖浏览器，验证 C2C + WS 实时推送 + SSE 流式 AI。
 *
 *   node scripts/smoke.mjs            # 默认 http://localhost:8080
 *   HCHAT_BASE_URL=http://api:8080 node scripts/smoke.mjs
 *
 * 需要服务端以 DEV_MODE=true 启动（默认 dev profile 已开启）。
 */
const BASE = (process.env.HCHAT_BASE_URL ?? 'http://localhost:8080').replace(/\/+$/, '');
const RUN_ID = Date.now().toString(36);
const APP_ID = process.env.HCHAT_APP_ID ?? 'app_demo';
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws';

let passed = 0;
const failures = [];

function ok(name, detail = '') {
  passed += 1;
  console.log(`  \u2713 ${name}${detail ? ` — ${detail}` : ''}`);
}

function assert(condition, name, detail = '') {
  if (condition) {
    ok(name, detail);
  } else {
    failures.push(name);
    console.error(`  \u2717 ${name}${detail ? ` — ${detail}` : ''}`);
  }
}

async function api(path, { method = 'GET', token, body, raw = false } = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  }
  if (raw) {
    return res;
  }
  if (res.status === 204) {
    return null;
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

async function devToken(externalUserId, displayName) {
  return api('/api/v1/auth/dev-token', {
    method: 'POST',
    body: { appId: APP_ID, externalUserId, displayName },
  });
}

class WsClient {
  constructor(label) {
    this.label = label;
    this.events = [];
    this.waiters = [];
  }

  connect(token) {
    return new Promise((resolve, reject) => {
      this.socket = new WebSocket(WS_URL);
      const timer = setTimeout(() => reject(new Error(`${this.label} WS 连接超时`)), 8000);
      this.socket.addEventListener('open', () => {
        this.send({ version: 1, type: 'auth', payload: { token } });
      });
      this.socket.addEventListener('message', (event) => {
        const frame = JSON.parse(event.data);
        this.events.push(frame);
        if (frame.type === 'auth.ok') {
          clearTimeout(timer);
          resolve(frame);
        }
        this.waiters = this.waiters.filter((waiter) => {
          if (waiter.match(frame)) {
            waiter.resolve(frame);
            return false;
          }
          return true;
        });
      });
      this.socket.addEventListener('error', () => {
        clearTimeout(timer);
        reject(new Error(`${this.label} WS 连接错误`));
      });
    });
  }

  send(frame) {
    this.socket.send(JSON.stringify(frame));
  }

  waitFor(type, timeoutMs = 6000) {
    const existing = this.events.find((e) => e.type === type);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`${this.label} 等待 ${type} 超时`)), timeoutMs);
      this.waiters.push({
        match: (frame) => frame.type === type,
        resolve: (frame) => {
          clearTimeout(timer);
          resolve(frame);
        },
      });
    });
  }

  close() {
    this.socket?.close();
  }
}

async function main() {
  console.log(`HChat 冒烟测试 → ${BASE}\n`);

  console.log('[1] Token 与健康检查');
  const health = await api('/health');
  assert(health.status === 'ok', '/health 可用', `db=${health.database.ok} ai=${health.aiProvider}`);
  const buyer = await devToken('buyer_1', '买家小明');
  const seller = await devToken('seller_1', '卖家老王');
  assert(Boolean(buyer.token && seller.token), '签发两个用户 Token', `buyer=${buyer.userId}`);

  console.log('\n[2] 建会话（dedupeKey 去重）');
  const conv = await api('/api/v1/conversations', {
    method: 'POST',
    token: buyer.token,
    body: {
      type: 'c2c',
      title: '商品咨询',
      dedupeKey: `product_1001:buyer_1:${RUN_ID}`,
      metadata: { productId: '1001', orderId: 'order_9001' },
      members: [{ externalUserId: 'seller_1', role: 'seller' }],
    },
  });
  assert(conv.members.length === 2, '会话包含买卖双方');
  const again = await api('/api/v1/conversations', {
    method: 'POST',
    token: buyer.token,
    body: { type: 'c2c', dedupeKey: `product_1001:buyer_1:${RUN_ID}`, members: [] },
  });
  assert(again.id === conv.id, 'dedupeKey 复用同一会话', again.id);

  console.log('\n[3] WebSocket 实时收发');
  const buyerWs = new WsClient('buyer');
  const sellerWs = new WsClient('seller');
  const buyerAuth = await buyerWs.connect(buyer.token);
  await sellerWs.connect(seller.token);
  assert(buyerAuth.payload.userId === buyer.userId, 'WS 首帧 auth 成功');

  buyerWs.send({
    version: 1,
    type: 'message.send',
    payload: {
      conversationId: conv.id,
      clientMsgId: `smoke-ws-${RUN_ID}`,
      type: 'text',
      content: { text: '你好，这件还包邮吗？' },
    },
  });
  const newForSeller = await sellerWs.waitFor('message.new');
  assert(
    newForSeller.payload.message.content.text === '你好，这件还包邮吗？',
    '卖家实时收到 message.new',
  );
  const newForBuyer = await buyerWs.waitFor('message.new');
  assert(newForBuyer.payload.message.clientMsgId === `smoke-ws-${RUN_ID}`, '发送端回显 message.new');

  console.log('\n[4] 已读 / 未读 / 撤回');
  const sellerList = await api('/api/v1/conversations', { token: seller.token });
  const sellerConv = sellerList.items.find((c) => c.id === conv.id);
  assert(sellerConv && sellerConv.unreadCount >= 1, '卖家未读数 +1', `unread=${sellerConv?.unreadCount}`);

  await api(`/api/v1/conversations/${conv.id}/read`, {
    method: 'POST',
    token: seller.token,
    body: { messageId: newForSeller.payload.message.id },
  });
  const readEvent = await buyerWs.waitFor('message.read');
  assert(readEvent.payload.userId === seller.userId, '买家收到已读回执');
  const sellerListAfter = await api('/api/v1/conversations', { token: seller.token });
  assert(
    sellerListAfter.items.find((c) => c.id === conv.id)?.unreadCount === 0,
    '已读后未读清零',
  );

  const recalled = await api(`/api/v1/messages/${newForSeller.payload.message.id}/recall`, {
    method: 'POST',
    token: buyer.token,
  });
  assert(recalled.status === 'recalled', '撤回消息成功');
  const recallEvent = await sellerWs.waitFor('message.recalled');
  assert(recallEvent.payload.messageId === recalled.id, '对端收到 message.recalled');

  console.log('\n[5] 历史分页');
  const page = await api(`/api/v1/conversations/${conv.id}/messages?limit=50`, { token: seller.token });
  assert(page.items.length >= 1, '拉取历史消息', `count=${page.items.length}`);

  buyerWs.close();
  sellerWs.close();

  console.log('\n[6] C2AI 流式回答（SSE）');
  const c2ai = await api('/api/v1/conversations', {
    method: 'POST',
    token: buyer.token,
    body: { type: 'c2ai', title: 'AI 助手', members: [] },
  });
  const res = await fetch(`${BASE}/api/v1/conversations/${c2ai.id}/ai/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${buyer.token}` },
    body: JSON.stringify({ text: '帮我总结一下这个订单需要确认的信息', clientMsgId: `smoke-ai-${RUN_ID}` }),
  });
  assert(res.ok, 'SSE 请求成功', `status=${res.status}`);
  const raw = await res.text();
  const events = raw
    .split('\n\n')
    .map((chunk) => chunk.trim())
    .filter((chunk) => chunk.startsWith('event:'))
    .map((chunk) => {
      const name = chunk.match(/^event:(.*)$/m)?.[1]?.trim();
      const data = chunk.match(/^data:(.*)$/m)?.[1]?.trim();
      return { name, data: data ? JSON.parse(data) : null };
    });
  const deltas = events.filter((e) => e.name === 'ai.delta');
  const done = events.find((e) => e.name === 'ai.done');
  assert(deltas.length > 1, '收到多个 ai.delta 分片', `chunks=${deltas.length}`);
  assert(Boolean(done?.data?.message?.content?.text), 'ai.done 返回落库消息');

  const aiHistory = await api(`/api/v1/conversations/${c2ai.id}/messages?limit=10`, { token: buyer.token });
  const aiMessages = aiHistory.items.filter((m) => m.senderType === 'ai');
  assert(aiMessages.length === 1, 'AI 回复已落库', aiMessages[0]?.content?.text?.slice(0, 24) ?? '');

  console.log('\n[7] 鉴权与错误格式');
  const unauthorized = await fetch(`${BASE}/api/v1/conversations`, {
    headers: { Authorization: 'Bearer not-a-real-token' },
  });
  const unauthorizedBody = await unauthorized.json();
  assert(
    unauthorized.status === 401 && unauthorizedBody.error?.code === 'unauthorized',
    '非法 Token 返回统一错误体',
    `code=${unauthorizedBody.error?.code}`,
  );
  assert(Boolean(unauthorizedBody.error?.requestId), '错误体包含 requestId');

  const missing = await fetch(`${BASE}/api/v1/conversations/123456789`, {
    headers: { Authorization: `Bearer ${buyer.token}` },
  });
  const missingBody = await missing.json();
  assert(
    missing.status === 404 && missingBody.error?.code === 'conversation_not_found',
    '不存在的会话返回 404',
    `code=${missingBody.error?.code}`,
  );

  console.log(`\n结果：${passed} 项通过，${failures.length} 项失败`);
  if (failures.length > 0) {
    console.error(`失败项：${failures.join(' / ')}`);
    process.exit(1);
  }
}

main().catch((error) => {
  console.error('\n冒烟测试异常：', error.message);
  process.exit(1);
});
