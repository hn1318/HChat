#!/usr/bin/env node
/**
 * 浏览器端到端校验（Chrome DevTools Protocol，无第三方依赖）。
 *
 * 前置：
 *   1. 服务端以 dev profile 运行在 8080
 *   2. playground 以 vite dev 运行在 5173
 *   3. Chrome 以 --remote-debugging-port=9222 启动
 *
 * 校验：自动登录 → 建会话 → 发消息 → 另一身份实时收到 → 回消息 → 截图
 * 用法：node scripts/ui-check.mjs [http://localhost:5173]
 */
import { writeFileSync } from 'node:fs';

const APP_URL = (process.argv[2] ?? 'http://localhost:5173').replace(/\/+$/, '');
const CDP = process.env.HCHAT_CDP ?? 'http://127.0.0.1:9222';
const OUT_DIR = process.env.HCHAT_SHOT_DIR ?? '.data/screenshots';

let passed = 0;
const failures = [];

function ok(name, detail = '') {
  passed += 1;
  console.log(`  ✓ ${name}${detail ? ` — ${detail}` : ''}`);
}

function fail(name, detail = '') {
  failures.push(name);
  console.error(`  ✗ ${name}${detail ? ` — ${detail}` : ''}`);
}

function assert(condition, name, detail = '') {
  if (condition) ok(name, detail);
  else fail(name, detail);
}

class CdpSession {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.id = 0;
    this.pending = new Map();
    this.errors = [];
  }

  async connect() {
    this.ws = new WebSocket(this.wsUrl);
    await new Promise((resolve, reject) => {
      this.ws.addEventListener('open', resolve, { once: true });
      this.ws.addEventListener('error', () => reject(new Error('CDP 连接失败')), { once: true });
    });
    this.ws.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.id && this.pending.has(message.id)) {
        const { resolve, reject } = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) reject(new Error(message.error.message));
        else resolve(message.result);
        return;
      }
      if (message.method === 'Runtime.exceptionThrown') {
        const details = message.params.exceptionDetails;
        const desc = details?.exception?.description ?? details?.text ?? 'exception';
        this.errors.push(String(desc).split('\n').slice(0, 3).join(' '));
      }
      if (message.method === 'Runtime.consoleAPICalled' && message.params.type === 'error') {
        this.errors.push(message.params.args.map((a) => a.value ?? a.description ?? '').join(' '));
      }
    });
    await this.send('Runtime.enable');
    await this.send('Page.enable');
  }

  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error(`CDP 超时: ${method}`));
        }
      }, 15000);
    });
  }

  /** 在页面里执行表达式，返回其 JSON 值。 */
  async evaluate(expression) {
    const result = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    if (result.exceptionDetails) {
      throw new Error(result.exceptionDetails.text ?? '页面执行异常');
    }
    return result.result.value;
  }

  async waitForExpression(expression, timeoutMs = 15000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const value = await this.evaluate(expression);
      if (value) return true;
      await new Promise((r) => setTimeout(r, 200));
    }
    return false;
  }

  async waitForText(text, timeoutMs = 15000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const found = await this.evaluate(`document.body.innerText.includes(${JSON.stringify(text)})`);
      if (found) return true;
      await new Promise((r) => setTimeout(r, 300));
    }
    return false;
  }

  async screenshot(path) {
    const { data } = await this.send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(path, Buffer.from(data, 'base64'));
  }

  close() {
    this.ws.close();
  }
}

async function newTab(url, label) {
  const target = await (
    await fetch(`${CDP}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' })
  ).json();
  const session = new CdpSession(target.webSocketDebuggerUrl);
  await session.connect();
  await session.send('Emulation.setDeviceMetricsOverride', {
    width: 1280,
    height: 860,
    deviceScaleFactor: 1,
    mobile: false,
  });
  console.log(`  · 打开标签页 ${label}`);
  return session;
}

/** 在页面里点击包含指定文案的按钮。 */
function clickByText(text) {
  return `(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes(${JSON.stringify(text)}));
    if (!btn) return false;
    btn.click();
    return true;
  })()`;
}

/** 点击匹配选择器的第一个元素。 */
function clickSelector(selector) {
  return `(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return false;
    el.click();
    return true;
  })()`;
}

/** 往 textarea 里写入内容并触发 React 的 onChange。 */
function typeMessage(text) {
  return `(() => {
    const el = document.querySelector('textarea');
    if (!el) return false;
    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
    setter.call(el, ${JSON.stringify(text)});
    el.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  })()`;
}

async function main() {
  console.log(`HChat 浏览器校验 → ${APP_URL}\n`);

  console.log('[1] 买家登录并创建会话');
  const buyer = await newTab(`${APP_URL}/?user=buyer_1&name=买家小明`, 'buyer');
  const loggedIn = await buyer.waitForText('商品会话');
  assert(loggedIn, '买家已登录（dev-token 换 token 成功）');
  if (!loggedIn) {
    throw new Error('登录失败，页面未进入聊天界面');
  }

  const clicked = await buyer.evaluate(clickByText('商品会话'));
  assert(clicked === true, '点击「+ 商品会话」创建 C2C 会话');
  const inputReady = await buyer.evaluate('Boolean(document.querySelector("textarea"))');
  assert(inputReady, '聊天输入框已渲染');

  const sessionReady = await buyer.waitForExpression(
    "Boolean(document.querySelector('.hchat-window__header'))",
    8000,
  );
  assert(sessionReady, '会话详情已加载（标题栏渲染）');

  console.log('\n[2] 买家发送消息');
  const text = `浏览器实时消息 ${new Date().toLocaleTimeString('zh-CN')}`;
  await buyer.evaluate(typeMessage(text));
  const sendable = await buyer.waitForExpression(
    "!document.querySelector('.hchat-input__send').disabled",
    5000,
  );
  assert(sendable, '发送按钮可用（连接就绪）');
  await buyer.evaluate(clickByText('发送'));
  const buyerSees = await buyer.waitForText(text);
  assert(buyerSees, '买家看到自己发送的消息');

  console.log('\n[3] 卖家实时接收');
  const seller = await newTab(`${APP_URL}/?user=seller_1&name=卖家老王`, 'seller');
  await seller.waitForText('商品会话');
  const sellerSees = await seller.waitForText(text, 12000);
  assert(sellerSees, '卖家通过 WebSocket 实时收到消息');

  console.log('\n[4] 卖家回复 / 已读回执');
  // 点会话列表里的那一项（而不是「+ 商品会话」建会话按钮）
  const opened = await seller.evaluate(clickSelector('.hchat-conversation'));
  assert(opened === true, '卖家打开已有会话');
  await new Promise((r) => setTimeout(r, 800));
  const sellerSeesHistory = await seller.waitForText(text);
  assert(sellerSeesHistory, '卖家进入会话后能看到历史消息');
  const reply = `卖家回复 ${new Date().toLocaleTimeString('zh-CN')}`;
  await seller.evaluate(typeMessage(reply));
  await seller.waitForExpression("!document.querySelector('.hchat-input__send').disabled", 5000);
  await seller.evaluate(clickByText('发送'));
  const buyerSeesReply = await buyer.waitForText(reply, 12000);
  assert(buyerSeesReply, '买家实时收到卖家回复');

  console.log('\n[5] AI 流式会话');
  await buyer.evaluate(clickByText('AI 会话'));
  await buyer.waitForText('AI 助手');
  await buyer.evaluate(typeMessage('用三句话介绍 HChat'));
  await new Promise((r) => setTimeout(r, 200));
  await buyer.evaluate(clickByText('发送'));
  const aiReplied = await buyer.waitForText('Mock AI', 20000);
  assert(aiReplied, 'AI 流式回答已渲染');

  console.log('\n[6] 无控制台报错');
  const jsErrors = [...buyer.errors, ...seller.errors].filter(
    (item) => !item.includes('favicon') && !item.toLowerCase().includes('websocket is closed'),
  );
  assert(jsErrors.length === 0, '页面无 JS 异常', jsErrors.slice(0, 2).join(' | '));

  await buyer.screenshot(`${OUT_DIR}/playground-buyer.png`);
  await seller.screenshot(`${OUT_DIR}/playground-seller.png`);
  ok('截图已保存', `${OUT_DIR}/playground-*.png`);

  buyer.close();
  seller.close();

  console.log(`\n结果：${passed} 项通过，${failures.length} 项失败`);
  if (failures.length > 0) {
    console.error(`失败项：${failures.join(' / ')}`);
    process.exit(1);
  }
}

main().catch((error) => {
  console.error('\n浏览器校验异常：', error.message);
  process.exit(1);
});
