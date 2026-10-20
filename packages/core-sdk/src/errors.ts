import type { ApiErrorBody } from 'hchat-shared-types';

/** 统一错误类型：所有 HTTP / WS / SSE 错误都归一化成它。 */
export class ChatError extends Error {
  readonly code: string;
  readonly status?: number;
  readonly requestId?: string;
  readonly details?: Record<string, unknown> | null;

  constructor(
    message: string,
    options: { code?: string; status?: number; requestId?: string; details?: Record<string, unknown> | null } = {},
  ) {
    super(message);
    this.name = 'ChatError';
    this.code = options.code ?? 'unknown_error';
    this.status = options.status;
    this.requestId = options.requestId;
    this.details = options.details ?? null;
  }

  static network(message = '网络请求失败'): ChatError {
    return new ChatError(message, { code: 'network_error' });
  }

  static fromBody(status: number, body: unknown, fallback = '请求失败'): ChatError {
    const parsed = body as ApiErrorBody | undefined;
    if (parsed && typeof parsed === 'object' && 'error' in parsed && parsed.error) {
      return new ChatError(parsed.error.message || fallback, {
        code: parsed.error.code,
        status,
        requestId: parsed.error.requestId,
        details: parsed.error.details ?? null,
      });
    }
    return new ChatError(fallback, { code: 'http_error', status });
  }
}

/** 连接类错误（WS 断开、SSE 中断）。 */
export class ChatConnectionError extends ChatError {
  constructor(message: string, code = 'connection_error') {
    super(message, { code });
    this.name = 'ChatConnectionError';
  }
}
