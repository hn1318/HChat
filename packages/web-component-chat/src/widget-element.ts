import {
  ChatError,
  createChatClient,
  type ChatClient,
  type Conversation,
  type Message,
} from 'hchat-core-sdk';
import { WIDGET_STYLES } from './styles';

const TEMPLATE = `
<style>${WIDGET_STYLES}</style>
<div class="header">
  <div class="header__title" part="title">HChat</div>
  <div class="header__meta"><span class="dot" data-state="idle"></span><span class="state">连接中…</span></div>
</div>
<div class="messages" part="messages" role="log" aria-live="polite"><div class="empty">正在创建会话…</div></div>
<div class="input">
  <button class="attach" type="button" title="发送图片或文件" aria-label="发送图片或文件">+</button>
  <input type="file" hidden />
  <textarea rows="1" placeholder="输入消息，Enter 发送" aria-label="输入消息"></textarea>
  <button class="send" type="button">发送</button>
</div>
`;

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function timeOf(iso?: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

function initial(name: string): string {
  return name.trim().slice(0, 1).toUpperCase() || '?';
}

/**
 * `<hchat-widget base-url app-id token mode>`：
 * 无框架依赖，可在任意页面（含 Vue/React/纯 HTML）中直接使用。
 */
export class HChatWidgetElement extends HTMLElement {
  static get observedAttributes(): string[] {
    return ['theme', 'height', 'token', 'base-url', 'app-id', 'conversation-id'];
  }

  private client: ChatClient | null = null;
  private conversation: Conversation | null = null;
  private messages: Message[] = [];
  private currentUserId: string | null = null;
  private streaming: string | null = null;
  private listEl!: HTMLElement;
  private titleEl!: HTMLElement;
  private stateEl!: HTMLElement;
  private dotEl!: HTMLElement;
  private textarea!: HTMLTextAreaElement;
  private sendButton!: HTMLButtonElement;
  private attachButton!: HTMLButtonElement;
  private fileInput!: HTMLInputElement;
  private typingTimer: ReturnType<typeof setTimeout> | null = null;
  private unsubscribers: Array<() => void> = [];

  connectedCallback(): void {
    if (!this.shadowRoot) {
      const root = this.attachShadow({ mode: 'open' });
      root.innerHTML = TEMPLATE;
      this.listEl = root.querySelector('.messages') as HTMLElement;
      this.titleEl = root.querySelector('.header__title') as HTMLElement;
      this.stateEl = root.querySelector('.state') as HTMLElement;
      this.dotEl = root.querySelector('.dot') as HTMLElement;
      this.textarea = root.querySelector('textarea') as HTMLTextAreaElement;
      this.sendButton = root.querySelector('.send') as HTMLButtonElement;
      this.attachButton = root.querySelector('.attach') as HTMLButtonElement;
      this.fileInput = root.querySelector('input[type=file]') as HTMLInputElement;
      this.bindEvents();
    }
    this.applyTheme();
    this.bootstrap();
  }

  disconnectedCallback(): void {
    this.unsubscribers.forEach((off) => off());
    this.unsubscribers = [];
    this.client?.disconnect();
  }

  attributeChangedCallback(name: string, oldValue: string | null, newValue: string | null): void {
    if (oldValue === newValue) return;
    if (name === 'theme') this.applyTheme();
    if (name === 'height') this.style.height = newValue ?? '520px';
    if (name === 'token' && newValue && this.client) this.client.setToken(newValue);
    if (name === 'conversation-id' && newValue) this.openConversation(newValue);
  }

  /** 供宿主页面调用：发送一条文本消息。 */
  async sendMessage(text: string): Promise<void> {
    if (!this.client || !this.conversation) return;
    await this.client.messages.send(this.conversation.id, { type: 'text', content: { text } });
  }

  /** 供宿主页面调用：切换会话。 */
  openConversation(conversationId: string): void {
    if (!this.client) return;
    void this.client.conversations
      .get(conversationId)
      .then((conversation) => {
        this.conversation = conversation;
        this.titleEl.textContent = this.titleOf(conversation);
        this.messages = [];
        return this.client?.messages.list(conversationId, { limit: 30 });
      })
      .then((page) => {
        if (page) {
          this.messages = page.items;
          this.renderMessages();
        }
      })
      .catch((error: Error) => this.showError(error.message));
  }

  /** Token 轮换（Token 过期时由宿主刷新后调用）。 */
  updateToken(token: string): void {
    this.setAttribute('token', token);
    this.client?.setToken(token);
  }

  private applyTheme(): void {
    const theme = this.getAttribute('theme');
    if (theme) this.setAttribute('theme', theme);
    const height = this.getAttribute('height');
    if (height) this.style.height = height;
  }

  private bindEvents(): void {
    const submit = () => {
      const text = this.textarea.value.trim();
      if (!text || !this.conversation) return;
      this.textarea.value = '';
      void this.sendWithAi(text);
    };
    this.sendButton.addEventListener('click', submit);
    this.textarea.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        submit();
      }
    });
    this.textarea.addEventListener('input', () => {
      this.client?.messages.typing(this.conversation?.id ?? '', true);
      if (this.typingTimer) clearTimeout(this.typingTimer);
      this.typingTimer = setTimeout(() => {
        this.client?.messages.typing(this.conversation?.id ?? '', false);
      }, 1500);
    });
    this.attachButton.addEventListener('click', () => this.fileInput.click());
    this.fileInput.addEventListener('change', () => {
      const file = this.fileInput.files?.[0];
      this.fileInput.value = '';
      if (!file || !this.client || !this.conversation) return;
      void this.client.files
        .upload(file)
        .then((uploaded) =>
          this.client?.messages.send(this.conversation!.id, {
            type: (uploaded.mimeType ?? '').startsWith('image/') ? 'image' : 'file',
            content: {
              url: uploaded.url,
              fileName: uploaded.fileName,
              mimeType: uploaded.mimeType,
              size: uploaded.size,
            },
          }),
        )
        .catch((error: Error) => this.showError(error.message));
    });
    this.listEl.addEventListener('click', (event) => {
      const target = (event.target as HTMLElement).closest('[data-recall]') as HTMLElement | null;
      if (target && this.client) {
        void this.client.messages.recall(target.dataset.recall ?? '');
      }
    });
  }

  private async sendWithAi(text: string): Promise<void> {
    if (!this.client || !this.conversation) return;
    if (this.getAttribute('mode') === 'c2ai') {
      this.streaming = '';
      this.renderMessages();
      try {
        await this.client.ai.stream({
          conversationId: this.conversation.id,
          text,
          onDelta: (delta) => {
            this.streaming = (this.streaming ?? '') + delta;
            this.renderMessages();
          },
        });
      } catch (error) {
        this.showError(error instanceof ChatError ? error.message : 'AI 调用失败');
      } finally {
        this.streaming = null;
        this.renderMessages();
      }
      return;
    }
    await this.client.messages.send(this.conversation.id, { type: 'text', content: { text } });
  }

  private bootstrap(): void {
    if (this.client) return;
    const baseUrl = this.getAttribute('base-url') ?? '';
    const appId = this.getAttribute('app-id') ?? '';
    const token = this.getAttribute('token') ?? '';
    if (!baseUrl || !appId || !token) {
      this.showError('缺少 base-url / app-id / token 属性');
      return;
    }
    this.client = createChatClient({ baseUrl, token, appId, autoConnect: false });

    this.unsubscribers.push(
      this.client.on('connection.state', (state) => {
        this.dotEl.dataset.state = state;
        this.stateEl.textContent =
          state === 'authenticated' ? '在线' : state === 'reconnecting' ? '重连中…' : '连接中…';
      }),
      this.client.on('auth.ok', (payload) => {
        this.currentUserId = payload.userId;
      }),
      this.client.on('message.new', ({ message }) => {
        if (this.conversation && message.conversationId === this.conversation.id) {
          if (!this.messages.some((item) => item.id === message.id)) {
            this.messages = [...this.messages, message];
            this.renderMessages();
          }
        }
        this.dispatchEvent(
          new CustomEvent('message', { detail: message, bubbles: true, composed: true }),
        );
      }),
      this.client.on('message.recalled', ({ messageId }) => {
        this.messages = this.messages.map((item) =>
          item.id === messageId ? { ...item, status: 'recalled' } : item,
        );
        this.renderMessages();
      }),
      this.client.on('error', (error) => this.showError(error.message)),
    );

    this.client.connect();

    const existing = this.getAttribute('conversation-id');
    if (existing) {
      this.openConversation(existing);
      return;
    }
    void this.createConversation();
  }

  private async createConversation(): Promise<void> {
    if (!this.client) return;
    const mode = (this.getAttribute('mode') as 'c2c' | 'c2ai' | 'c2c_ai_assist' | null) ?? 'c2c';
    const metadata: Record<string, unknown> = {};
    const productId = this.getAttribute('product-id');
    const orderId = this.getAttribute('order-id');
    const sellerId = this.getAttribute('seller-id');
    if (productId) metadata.productId = productId;
    if (orderId) metadata.orderId = orderId;
    if (sellerId) metadata.sellerId = sellerId;
    try {
      const conversation = await this.client.conversations.create({
        type: mode,
        title: this.getAttribute('title') ?? undefined,
        metadata,
        dedupeKey: this.getAttribute('dedupe-key') ?? undefined,
        agentId: this.getAttribute('agent-id') ?? undefined,
        members: sellerId ? [{ externalUserId: sellerId, role: 'seller' }] : undefined,
      });
      this.conversation = conversation;
      this.titleEl.textContent = this.titleOf(conversation);
      const page = await this.client.messages.list(conversation.id, { limit: 30 });
      this.messages = page.items;
      this.renderMessages();
      this.dispatchEvent(
        new CustomEvent('ready', { detail: conversation, bubbles: true, composed: true }),
      );
    } catch (error) {
      this.showError(error instanceof Error ? error.message : '创建会话失败');
    }
  }

  private titleOf(conversation: Conversation): string {
    if (conversation.title) return conversation.title;
    const other = conversation.members?.find((member) => member.userId !== this.currentUserId);
    return other?.displayName ?? other?.externalUserId ?? (conversation.type === 'c2ai' ? 'AI 助手' : '会话');
  }

  private renderMessages(): void {
    if (!this.conversation) {
      this.listEl.innerHTML = '<div class="empty">正在创建会话…</div>';
      return;
    }
    if (this.messages.length === 0 && this.streaming === null) {
      this.listEl.innerHTML = '<div class="empty">还没有消息，发一条试试</div>';
      return;
    }

    const html: string[] = [];
    let lastDay = '';
    for (const message of this.messages) {
      const day = new Date(message.createdAt).toDateString();
      if (day !== lastDay) {
        lastDay = day;
        html.push(`<div class="day">${escapeHtml(new Date(message.createdAt).toLocaleDateString('zh-CN'))}</div>`);
      }
      if (message.type === 'system') {
        html.push(`<div class="system"><span>${escapeHtml(message.content.text ?? '')}</span></div>`);
        continue;
      }
      const own = message.senderId != null && message.senderId === this.currentUserId;
      const name = message.senderType === 'ai' ? 'AI 助手' : own ? '我' : '对方';
      const bubble =
        message.status === 'recalled'
          ? '<div class="bubble bubble--recalled">消息已撤回</div>'
          : `<div class="bubble${own ? ' bubble--own' : ''}">${this.contentHtml(message)}</div>`;
      html.push(`
        <div class="row${own ? ' row--own' : ''}">
          ${own ? '' : `<span class="avatar" style="background:#5b8def">${escapeHtml(initial(name))}</span>`}
          <div class="row__body">
            ${own ? '' : `<div class="row__name">${escapeHtml(name)}</div>`}
            ${bubble}
            <div class="row__footer">
              <span>${timeOf(message.createdAt)}</span>
              ${own && message.status === 'sent' ? `<button class="link-button" data-recall="${message.id}">撤回</button>` : ''}
            </div>
          </div>
        </div>`);
    }

    if (this.streaming !== null) {
      html.push(`
        <div class="row">
          <span class="avatar" style="background:#5b8def">AI</span>
          <div class="row__body">
            <div class="row__name">AI 助手</div>
            <div class="bubble bubble--streaming">${escapeHtml(this.streaming || '正在思考…')}</div>
          </div>
        </div>`);
    }

    this.listEl.innerHTML = html.join('');
    this.listEl.scrollTop = this.listEl.scrollHeight;
  }

  private contentHtml(message: Message): string {
    const content = message.content;
    if (message.type === 'image' && content.url) {
      return `<a href="${escapeHtml(String(content.url))}" target="_blank" rel="noreferrer noopener"><img src="${escapeHtml(
        String(content.url),
      )}" alt="图片" loading="lazy" /></a>`;
    }
    if (message.type === 'file' && content.url) {
      return `<a href="${escapeHtml(String(content.url))}" target="_blank" rel="noreferrer noopener">📎 ${escapeHtml(
        String(content.fileName ?? '文件'),
      )}</a>`;
    }
    if (message.type === 'card') {
      return `<div class="card"><div class="card__body">
        <div class="card__title">${escapeHtml(String(content.title ?? '卡片消息'))}</div>
        ${content.subtitle ? `<div class="card__subtitle">${escapeHtml(String(content.subtitle))}</div>` : ''}
        ${content.price ? `<div class="card__price">${escapeHtml(String(content.price))}</div>` : ''}
        ${content.url ? `<a href="${escapeHtml(String(content.url))}" target="_blank" rel="noreferrer noopener">查看详情</a>` : ''}
      </div></div>`;
    }
    return escapeHtml(String(content.text ?? ''));
  }

  private showError(message: string): void {
    this.listEl.innerHTML = `<div class="empty">${escapeHtml(message)}</div>`;
    this.dispatchEvent(
      new CustomEvent('error', { detail: new ChatError(message), bubbles: true, composed: true }),
    );
  }
}
