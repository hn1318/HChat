import type { UploadResult } from 'hchat-shared-types';
import { ChatError } from './errors';

export type Query = Record<string, string | number | boolean | null | undefined>;

export interface HttpClientOptions {
  baseUrl: string;
  getToken: () => string | null;
  fetchImpl?: typeof fetch;
}

export class HttpClient {
  private readonly baseUrl: string;
  private readonly getToken: () => string | null;
  private readonly fetchImpl: typeof fetch;

  constructor(options: HttpClientOptions) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, '');
    this.getToken = options.getToken;
    const impl = options.fetchImpl ?? globalThis.fetch;
    if (!impl) {
      throw new ChatError('当前环境没有 fetch，请在初始化时传入 fetch 实现', { code: 'fetch_unavailable' });
    }
    // 浏览器要求 fetch 的接收者是 window/worker 全局对象，
    // 若以 this.fetchImpl(...) 方式调用会抛 "Illegal invocation"。
    this.fetchImpl = impl.bind(globalThis) as typeof fetch;
  }

  url(path: string, query?: Query): string {
    const params = new URLSearchParams();
    if (query) {
      for (const [key, value] of Object.entries(query)) {
        if (value !== undefined && value !== null && value !== '') {
          params.append(key, String(value));
        }
      }
    }
    const queryString = params.toString();
    return `${this.baseUrl}${path}${queryString ? `?${queryString}` : ''}`;
  }

  /** WebSocket 地址（http→ws / https→wss）。 */
  wsUrl(path = '/ws'): string {
    return `${this.baseUrl.replace(/^http/, 'ws')}${path}`;
  }

  private headers(extra?: Record<string, string>): Record<string, string> {
    const headers: Record<string, string> = { ...extra };
    const token = this.getToken();
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    return headers;
  }

  async request<T>(
    method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
    path: string,
    options: { body?: unknown; query?: Query; signal?: AbortSignal } = {},
  ): Promise<T> {
    const hasBody = options.body !== undefined;
    const response = await this.send(
      this.url(path, options.query),
      {
        method,
        headers: this.headers(hasBody ? { 'Content-Type': 'application/json' } : undefined),
        body: hasBody ? JSON.stringify(options.body) : undefined,
        signal: options.signal,
      },
      path,
    );
    return this.parse<T>(response);
  }

  async upload(file: Blob, fileName?: string): Promise<UploadResult> {
    const form = new FormData();
    form.append('file', file, fileName ?? (file as File).name ?? 'file');
    const response = await this.send(
      this.url('/api/v1/files/upload'),
      { method: 'POST', headers: this.headers(), body: form },
      '/api/v1/files/upload',
    );
    return this.parse<UploadResult>(response);
  }

  /** 打开 SSE 流（POST + text/event-stream），由上层解析分片。 */
  async openStream(path: string, body: unknown, signal?: AbortSignal): Promise<Response> {
    const response = await this.send(
      this.url(path),
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
        body: JSON.stringify(body),
        signal,
      },
      path,
    );
    return response;
  }

  private async send(url: string, init: RequestInit, path: string): Promise<Response> {
    let response: Response;
    try {
      response = await this.fetchImpl(url, init);
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        throw new ChatError('请求已取消', { code: 'aborted' });
      }
      throw ChatError.network(`网络请求失败：${path}`);
    }
    if (!response.ok) {
      const payload = await this.readErrorBody(response);
      throw ChatError.fromBody(response.status, payload, `请求失败（${response.status}）`);
    }
    return response;
  }

  private async readErrorBody(response: Response): Promise<unknown> {
    try {
      const text = await response.text();
      if (!text) return null;
      try {
        return JSON.parse(text);
      } catch {
        return { error: { code: 'http_error', message: text.slice(0, 200) } };
      }
    } catch {
      return null;
    }
  }

  private async parse<T>(response: Response): Promise<T> {
    if (response.status === 204) {
      return undefined as T;
    }
    const contentType = response.headers.get('content-type') ?? '';
    if (contentType.includes('application/json')) {
      return (await response.json()) as T;
    }
    return (await response.text()) as unknown as T;
  }
}
