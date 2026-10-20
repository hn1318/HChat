import { describe, expect, it, vi } from 'vitest';
import { HChatServerSdk, HChatServerSdkError } from './index';

describe('Webhook 验签', () => {
  const secret = 'whsec_test';
  const body = '{"hello":"world"}';

  it('与服务端 Java 实现保持一致（同一参考向量）', () => {
    expect(HChatServerSdk.sign(secret, 1700000000, body)).toBe(
      'f592bbf3951cfc94e560eecfb5d9dd4da6b0fff2e626235f8ab4b54860925d0b',
    );
  });

  it('签名正确且在容忍窗口内时通过', () => {
    const timestamp = 1700000000;
    const payload = HChatServerSdk.verifyWebhook(
      {
        'X-HChat-Signature': `sha256=${HChatServerSdk.sign(secret, timestamp, body)}`,
        'X-HChat-Timestamp': String(timestamp),
      },
      body,
      { secret, now: timestamp + 10 },
    );
    expect(payload).toEqual({ hello: 'world' });
  });

  it('拒绝被篡改的 body', () => {
    const timestamp = 1700000000;
    const headers = {
      'x-hchat-signature': `sha256=${HChatServerSdk.sign(secret, timestamp, body)}`,
      'x-hchat-timestamp': String(timestamp),
    };
    expect(() => HChatServerSdk.verifyWebhook(headers, '{"hello":"world!"}', { secret, now: timestamp })).toThrow(
      HChatServerSdkError,
    );
  });

  it('拒绝过期时间戳（防重放）', () => {
    const timestamp = 1700000000;
    const headers = {
      'x-hchat-signature': `sha256=${HChatServerSdk.sign(secret, timestamp, body)}`,
      'x-hchat-timestamp': String(timestamp),
    };
    expect(() =>
      HChatServerSdk.verifyWebhook(headers, body, { secret, now: timestamp + 3600 }),
    ).toThrowError(/重放/);
  });

  it('缺少签名头时报错', () => {
    expect(() => HChatServerSdk.verifyWebhook({}, body, { secret })).toThrowError(/缺少/);
  });
});

describe('签发 Token', () => {
  it('把 appSecret 放在服务端请求体里，并解析响应', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          token: 'jwt',
          tokenType: 'Bearer',
          expiresIn: 3600,
          expiresAt: '2026-01-01T00:00:00Z',
          userId: '1',
          externalUserId: 'u-1',
          appId: 'app_demo',
          tenantId: 'default',
          scopes: ['chat'],
        }),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ),
    );
    const sdk = new HChatServerSdk({
      baseUrl: 'http://localhost:8080/',
      appId: 'app_demo',
      appSecret: 'dev-secret-hchat',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });

    const result = await sdk.issueToken({ externalUserId: 'u-1', displayName: '买家' });

    expect(result.token).toBe('jwt');
    const [url, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://localhost:8080/api/v1/auth/token');
    expect(JSON.parse(String(init.body))).toMatchObject({
      appId: 'app_demo',
      appSecret: 'dev-secret-hchat',
      externalUserId: 'u-1',
    });
  });

  it('服务端错误转成带错误码的异常', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ error: { code: 'invalid_app_secret', message: 'appSecret 校验失败' } }), {
        status: 401,
        headers: { 'content-type': 'application/json' },
      }),
    );
    const sdk = new HChatServerSdk({
      baseUrl: 'http://localhost:8080',
      appId: 'app_demo',
      appSecret: 'wrong',
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });

    await expect(sdk.issueToken({ externalUserId: 'u-1' })).rejects.toMatchObject({
      code: 'invalid_app_secret',
      status: 401,
    });
  });
});
