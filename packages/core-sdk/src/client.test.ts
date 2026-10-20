import { describe, expect, it, vi } from 'vitest';
import { createChatClient } from './client';
import { ChatError } from './errors';
import { consumeSse } from './sse';

type Listener = ((event: { data?: string }) => void) | null;

class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static readonly OPEN = 1;
  readyState = 0;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: Listener = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;

  constructor(readonly url: string) {
    MockWebSocket.instances.push(this);
  }

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {
    this.readyState = 3;
    this.onclose?.();
  }

  open(): void {
    this.readyState = 1;
    this.onopen?.();
  }

  receive(frame: unknown): void {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

describe('ChatClient REST', () => {
  it('带上 Bearer Token 并拼接查询参数', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ items: [], hasMore: false, nextCursor: null }),
    );
    const client = createChatClient({
      baseUrl: 'http://localhost:8080/',
      token: 'tok_123',
      fetch: fetchMock as unknown as typeof fetch,
      autoConnect: false,
    });

    await client.conversations.list({ limit: 10, cursor: 'abc' });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const parsed = new URL(url);
    expect(parsed.origin + parsed.pathname).toBe('http://localhost:8080/api/v1/conversations');
    expect(parsed.searchParams.get('limit')).toBe('10');
    expect(parsed.searchParams.get('cursor')).toBe('abc');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer tok_123');
  });

  it('把服务端错误体归一化成 ChatError', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ error: { code: 'not_a_member', message: '不是该会话成员', requestId: 'r-1' } }, 403),
    );
    const client = createChatClient({
      baseUrl: 'http://localhost:8080',
      token: 'tok',
      fetch: fetchMock as unknown as typeof fetch,
      autoConnect: false,
    });

    await expect(client.messages.list('1')).rejects.toMatchObject({
      code: 'not_a_member',
      status: 403,
      requestId: 'r-1',
    });
  });

  it('发送消息自动生成 clientMsgId（幂等键）', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        id: '1',
        conversationId: '2',
        senderType: 'user',
        type: 'text',
        content: { text: 'hi' },
        status: 'sent',
        createdAt: '2026-01-01T00:00:00Z',
      }),
    );
    const client = createChatClient({
      baseUrl: 'http://localhost:8080',
      token: 'tok',
      fetch: fetchMock as unknown as typeof fetch,
      autoConnect: false,
    });

    await client.messages.send('2', { content: { text: 'hi' } });

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit;
    const body = JSON.parse(String(init.body)) as { clientMsgId: string; type: string };
    expect(body.clientMsgId).toMatch(/[a-z0-9-]{8,}/i);
    expect(body.type).toBe('text');
  });

  it('网络异常转成 network_error', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const client = createChatClient({
      baseUrl: 'http://localhost:8080',
      token: 'tok',
      fetch: fetchMock as unknown as typeof fetch,
      autoConnect: false,
    });

    await expect(client.conversations.list()).rejects.toBeInstanceOf(ChatError);
    await expect(client.conversations.list()).rejects.toMatchObject({ code: 'network_error' });
  });
});

describe('ChatClient WebSocket', () => {
  it('连接后首帧发送 auth，并转发 message.new', async () => {
    MockWebSocket.instances = [];
    const client = createChatClient({
      baseUrl: 'http://localhost:8080',
      token: 'tok_ws',
      fetch: vi.fn() as unknown as typeof fetch,
      WebSocket: MockWebSocket as unknown as typeof WebSocket,
    });

    const socket = MockWebSocket.instances[0];
    expect(socket?.url).toBe('ws://localhost:8080/ws');
    socket?.open();
    expect(JSON.parse(socket?.sent[0] ?? '{}')).toMatchObject({
      version: 1,
      type: 'auth',
      payload: { token: 'tok_ws' },
    });

    const received: string[] = [];
    client.on('message.new', ({ message }) => received.push(message.id));
    socket?.receive({
      version: 1,
      type: 'auth.ok',
      payload: { userId: '1', appId: 'app', tenantId: 'default', externalUserId: 'e', scopes: ['chat'] },
    });
    expect(client.connectionState).toBe('authenticated');

    socket?.receive({
      version: 1,
      type: 'message.new',
      payload: { message: { id: 'm-1', content: { text: 'hi' }, type: 'text' } },
    });
    expect(received).toEqual(['m-1']);
    client.disconnect();
  });
});

describe('SSE 解析', () => {
  it('分片到达时仍能正确切分事件', async () => {
    const payload =
      'event: ai.delta\ndata: {"delta":"你"}\n\n' +
      'event: ai.delta\ndata: {"delta":"好"}\n\n' +
      'event: ai.done\ndata: {"runId":"1","message":{"id":"9"}}\n\n';
    const encoder = new TextEncoder();
    const chunks = [payload.slice(0, 25), payload.slice(25, 60), payload.slice(60)];
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
        controller.close();
      },
    });
    const events: Array<{ event: string; data: string }> = [];

    await consumeSse(new Response(stream), (event, data) => events.push({ event, data }));

    expect(events.map((e) => e.event)).toEqual(['ai.delta', 'ai.delta', 'ai.done']);
    expect(JSON.parse(events[2]?.data ?? '{}')).toMatchObject({ runId: '1' });
  });
});
