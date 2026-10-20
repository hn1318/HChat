import { createHmac, timingSafeEqual } from 'node:crypto';
import type { TokenResponse } from 'hchat-shared-types';

export interface ServerSdkOptions {
  /** 服务端地址，例如 http://localhost:8080 */
  baseUrl: string;
  appId: string;
  /** 仅在业务方后端使用，绝不能下发到浏览器 */
  appSecret: string;
  fetchImpl?: typeof fetch;
  /** 请求超时（毫秒），默认 10s */
  timeoutMs?: number;
}

export interface IssueTokenInput {
  externalUserId: string;
  displayName?: string;
  avatarUrl?: string;
  metadata?: Record<string, unknown>;
  ttlSeconds?: number;
}

export interface WebhookPayload<T = Record<string, unknown>> {
  id: string;
  type: string;
  createdAt: string;
  data: T;
}

export interface VerifyOptions {
  secret: string;
  /** 允许的时间偏差（秒），默认 300，用于防重放 */
  toleranceSeconds?: number;
  /** 当前时间（秒），便于测试 */
  now?: number;
}

export interface HChatSdkErrorBody {
  error?: { code?: string; message?: string; requestId?: string };
}

export class HChatServerSdkError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;

  constructor(message: string, options: { status: number; code?: string; requestId?: string }) {
    super(message);
    this.name = 'HChatServerSdkError';
    this.status = options.status;
    this.code = options.code ?? 'http_error';
    this.requestId = options.requestId;
  }
}

/**
 * 业务方后端 SDK：
 * 1. 用 appId + appSecret 为具体用户签发短期 Token
 * 2. 校验 Webhook 的 HMAC 签名与时间戳
 */
export class HChatServerSdk {
  private readonly baseUrl: string;
  private readonly appId: string;
  private readonly appSecret: string;
  private readonly fetchImpl: typeof fetch;
  private readonly timeoutMs: number;

  constructor(options: ServerSdkOptions) {
    if (!options.baseUrl || !options.appId || !options.appSecret) {
      throw new Error('baseUrl / appId / appSecret 均为必填');
    }
    this.baseUrl = options.baseUrl.replace(/\/+$/, '');
    this.appId = options.appId;
    this.appSecret = options.appSecret;
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch;
    this.timeoutMs = options.timeoutMs ?? 10_000;
  }

  /** 为业务系统里的用户换取 HChat 短期 Token。 */
  async issueToken(input: IssueTokenInput): Promise<TokenResponse> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const response = await this.fetchImpl(`${this.baseUrl}/api/v1/auth/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          appId: this.appId,
          appSecret: this.appSecret,
          externalUserId: input.externalUserId,
          displayName: input.displayName,
          avatarUrl: input.avatarUrl,
          metadata: input.metadata,
          ttlSeconds: input.ttlSeconds,
        }),
        signal: controller.signal,
      });
      const text = await response.text();
      const payload = text ? (JSON.parse(text) as TokenResponse & HChatSdkErrorBody) : ({} as HChatSdkErrorBody);
      if (!response.ok) {
        throw new HChatServerSdkError(payload.error?.message ?? `签发 Token 失败（${response.status}）`, {
          status: response.status,
          code: payload.error?.code,
          requestId: payload.error?.requestId,
        });
      }
      return payload as TokenResponse;
    } finally {
      clearTimeout(timer);
    }
  }

  /**
   * 校验 Webhook：签名 + 时间戳。
   *
   * @param headers 原始请求头（大小写不敏感）
   * @param rawBody 原始请求体字符串（必须未经过 JSON 重新序列化）
   * @throws 校验失败时抛出 HChatServerSdkError
   */
  static verifyWebhook<T = Record<string, unknown>>(
    headers: Record<string, string | string[] | undefined>,
    rawBody: string,
    options: VerifyOptions,
  ): WebhookPayload<T> {
    const signature = header(headers, 'x-hchat-signature');
    const timestamp = header(headers, 'x-hchat-timestamp');
    if (!signature || !timestamp) {
      throw new HChatServerSdkError('缺少 Webhook 签名头', { status: 400, code: 'missing_signature' });
    }
    const provided = signature.replace(/^sha256=/, '');
    const expected = HChatServerSdk.sign(options.secret, Number(timestamp), rawBody);
    const providedBuffer = Buffer.from(provided, 'utf8');
    const expectedBuffer = Buffer.from(expected, 'utf8');
    if (providedBuffer.length !== expectedBuffer.length || !timingSafeEqual(providedBuffer, expectedBuffer)) {
      throw new HChatServerSdkError('Webhook 签名不匹配', { status: 401, code: 'invalid_signature' });
    }
    const tolerance = options.toleranceSeconds ?? 300;
    const now = options.now ?? Math.floor(Date.now() / 1000);
    if (Math.abs(now - Number(timestamp)) > tolerance) {
      throw new HChatServerSdkError('Webhook 时间戳超出容忍窗口（疑似重放）', {
        status: 401,
        code: 'stale_timestamp',
      });
    }
    try {
      return JSON.parse(rawBody) as WebhookPayload<T>;
    } catch {
      throw new HChatServerSdkError('Webhook 请求体不是合法 JSON', { status: 400, code: 'invalid_body' });
    }
  }

  /** 与服务端一致的签名算法：HMAC-SHA256(secret, `${timestamp}.${body}`) 的 hex。 */
  static sign(secret: string, timestamp: number, body: string): string {
    return createHmac('sha256', secret).update(`${timestamp}.${body}`, 'utf8').digest('hex');
  }
}

/** HTTP 头大小写不敏感：逐个 key 比对比更可靠（兼容 X-HChat-Signature 等写法）。 */
function header(headers: Record<string, string | string[] | undefined>, name: string): string | undefined {
  const target = name.toLowerCase();
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() !== target) continue;
    return Array.isArray(value) ? value[0] : value;
  }
  return undefined;
}

export function createServerSdk(options: ServerSdkOptions): HChatServerSdk {
  return new HChatServerSdk(options);
}
