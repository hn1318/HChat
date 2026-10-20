/**
 * ai-agent-demo：演示「业务方后端签发 Token → 前端/服务端 SDK 建 C2AI 会话 → SSE 流式回答」。
 *
 * 运行：
 *   cd examples/ai-agent-demo && pnpm build && node dist/index.js
 *   HCHAT_BASE_URL=http://localhost:8080 HCHAT_QUESTION="帮我总结一下待办" node dist/index.js
 */
import { createChatClient } from 'hchat-core-sdk';
import { createServerSdk } from 'hchat-server-sdk-node';

const baseUrl = process.env.HCHAT_BASE_URL ?? 'http://localhost:8080';
const appId = process.env.HCHAT_APP_ID ?? 'app_demo';
const appSecret = process.env.HCHAT_APP_SECRET ?? 'dev-secret-hchat';
const externalUserId = process.env.HCHAT_USER_ID ?? 'demo_user';
const question = process.env.HCHAT_QUESTION ?? '用三句话介绍一下 HChat 能做什么';

async function main(): Promise<void> {
  console.log(`→ 服务端：${baseUrl}`);

  // 1. 业务方后端用 appSecret 换短期 Token（appSecret 永远不下发到前端）
  const sdk = createServerSdk({ baseUrl, appId, appSecret });
  const auth = await sdk.issueToken({ externalUserId, displayName: 'AI 演示用户' });
  console.log(`✓ Token 已签发，有效期 ${auth.expiresIn}s，内部用户 ${auth.userId}`);

  const client = createChatClient({
    baseUrl,
    token: auth.token,
    appId,
    autoConnect: false,
  });

  // 2. 复用或创建 C2AI 会话
  const conversation = await client.conversations.create({
    type: 'c2ai',
    title: 'AI 演示会话',
    dedupeKey: `demo:${externalUserId}`,
  });
  console.log(`✓ 会话：${conversation.id}`);

  const agents = await client.ai.listAgents();
  const agent = agents[0];
  console.log(`✓ 可用智能体：${agents.map((item) => item.name).join('、') || '（使用内置默认）'}`);

  // 3. SSE 流式回答
  console.log(`\n问：${question}\n答：`);
  const started = Date.now();
  let chunks = 0;
  const done = await client.ai.stream({
    conversationId: conversation.id,
    text: question,
    agentId: agent?.id,
    onDelta: (delta) => {
      chunks += 1;
      process.stdout.write(delta);
    },
  });

  console.log(`\n\n✓ 完成：${chunks} 个分片，耗时 ${Date.now() - started}ms`);
  console.log(`  消息 ID：${done.message.id}`);
  if (done.usage && Object.keys(done.usage).length > 0) {
    console.log(`  usage：${JSON.stringify(done.usage)}`);
  }

  // 4. 校验落库
  const page = await client.messages.list(conversation.id, { limit: 10 });
  console.log(`✓ 会话内共 ${page.items.length} 条消息：`);
  for (const message of page.items) {
    const who = message.senderType === 'ai' ? 'AI' : message.senderType === 'system' ? 'SYS' : 'USER';
    const text = String(message.content.text ?? '').replace(/\s+/g, ' ').slice(0, 60);
    console.log(`  [${who}] ${text}`);
  }
  client.disconnect();
}

main().catch((error: unknown) => {
  console.error('\n✗ 运行失败：', error instanceof Error ? error.message : error);
  process.exit(1);
});
