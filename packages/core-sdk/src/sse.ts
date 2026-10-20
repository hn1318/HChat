import { ChatConnectionError } from './errors';

export type SseEventListener = (event: string, data: string) => void;

/**
 * 解析 text/event-stream 响应体（POST + fetch 流式读取，EventSource 不支持 POST）。
 */
export async function consumeSse(response: Response, onEvent: SseEventListener): Promise<void> {
  if (!response.body) {
    throw new ChatConnectionError('SSE 响应没有可读流', 'sse_error');
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  const dispatch = (block: string) => {
    const lines = block.split('\n');
    let event = 'message';
    const dataLines: string[] = [];
    for (const line of lines) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''));
      }
    }
    if (dataLines.length > 0) {
      onEvent(event, dataLines.join('\n'));
    }
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, '\n');
      let index = buffer.indexOf('\n\n');
      while (index >= 0) {
        const block = buffer.slice(0, index);
        buffer = buffer.slice(index + 2);
        if (block.trim()) dispatch(block);
        index = buffer.indexOf('\n\n');
      }
    }
    if (buffer.trim()) dispatch(buffer);
  } finally {
    reader.releaseLock();
  }
}
