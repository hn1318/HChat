import { ChatProvider, ChatWindow, ConversationList, MessageInput, useChat } from 'hchat-react-chat';
import type { TokenResponse } from 'hchat-shared-types';
import { useCallback, useEffect, useMemo, useState } from 'react';

const BASE_URL = (import.meta.env.VITE_HCHAT_BASE_URL as string | undefined) ?? 'http://localhost:8080';
const APP_ID = (import.meta.env.VITE_HCHAT_APP_ID as string | undefined) ?? 'app_demo';
const STORAGE_KEY = 'hchat.playground.session';

interface Session {
  token: string;
  userId: string;
  externalUserId: string;
  displayName: string;
  appId: string;
}

type ThemeMode = 'auto' | 'light' | 'dark';

function readSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

async function devLogin(appId: string, externalUserId: string, displayName: string): Promise<Session> {
  const response = await fetch(`${BASE_URL}/api/v1/auth/dev-token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ appId, externalUserId, displayName }),
  });
  const payload = (await response.json()) as TokenResponse & { error?: { message?: string } };
  if (!response.ok) {
    throw new Error(payload.error?.message ?? `登录失败（${response.status}）`);
  }
  return {
    token: payload.token,
    userId: payload.userId,
    externalUserId: payload.externalUserId,
    displayName,
    appId: payload.appId,
  };
}

export function App() {
  const [session, setSession] = useState<Session | null>(() => readSession());
  const [theme, setTheme] = useState<ThemeMode>('auto');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  // 支持 ?user=seller_1&name=卖家老王 快速开第二个身份（验证 C2C 双端实时）
  useEffect(() => {
    if (session) return;
    const params = new URLSearchParams(window.location.search);
    const externalUserId = params.get('user');
    if (!externalUserId) return;
    const displayName = params.get('name') ?? externalUserId;
    void devLogin(APP_ID, externalUserId, displayName)
      .then((next) => {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
        setSession(next);
      })
      .catch((err: Error) => setError(err.message));
  }, [session]);

  const logout = useCallback(() => {
    sessionStorage.removeItem(STORAGE_KEY);
    setSession(null);
  }, []);

  if (!session) {
    return (
      <div className="shell shell--login">
        <LoginCard
          error={error}
          onLogin={(next) => {
            sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next));
            setSession(next);
            setError(null);
          }}
          onError={setError}
        />
        <p className="login-hint">
          服务端：<code>{BASE_URL}</code>，appId：<code>{APP_ID}</code>
          <br />
          需要以 <code>dev</code> profile 启动服务端（DEV_MODE=true），才能使用免密登录。
        </p>
      </div>
    );
  }

  return (
    <ChatProvider baseUrl={BASE_URL} token={session.token} appId={session.appId} onError={(err) => setError(err.message)}>
      <div className="shell hchat-root" data-theme={theme}>
        <header className="topbar">
          <div className="topbar__brand">
            <span className="topbar__logo">HC</span>
            <span>HChat Playground</span>
          </div>
          <div className="topbar__right">
            <span className="topbar__user">{session.displayName}</span>
            <select
              className="topbar__theme"
              value={theme}
              aria-label="主题"
              onChange={(event) => setTheme(event.target.value as ThemeMode)}
            >
              <option value="auto">跟随系统</option>
              <option value="light">浅色</option>
              <option value="dark">深色</option>
            </select>
            <button type="button" className="ghost" onClick={logout}>
              退出
            </button>
          </div>
        </header>

        <div className="layout">
          <aside className="sidebar">
            <SidebarActions externalUserId={session.externalUserId} />
            <ConversationList emptyText="还没有会话，先创建一个" />
          </aside>
          <main className="main">
            <ChatWindow />
            <MessageInput />
          </main>
        </div>

        {error ? (
          <div className="toast" role="alert">
            {error}
            <button type="button" onClick={() => setError(null)} aria-label="关闭提示">
              ×
            </button>
          </div>
        ) : null}
      </div>
    </ChatProvider>
  );
}

function SidebarActions({ externalUserId }: { externalUserId: string }) {
  const { client, openConversation, sendMessage, activeConversation } = useChat();
  const [busy, setBusy] = useState(false);

  const createConversation = async (type: 'c2c' | 'c2ai') => {
    setBusy(true);
    try {
      const stamp = Date.now().toString(36);
      const conversation = await client.conversations.create({
        type,
        title: type === 'c2c' ? `商品咨询 ${stamp.slice(-4)}` : 'AI 助手',
        metadata: type === 'c2c' ? { productId: `product_${stamp}` } : {},
        dedupeKey: type === 'c2c' ? `p:${stamp}:${externalUserId}` : undefined,
        members:
          type === 'c2c'
            ? [
                { externalUserId: 'seller_1', role: 'seller' },
                { externalUserId: 'seller_2', role: 'seller' },
              ]
            : undefined,
      });
      openConversation(conversation.id);
    } finally {
      setBusy(false);
    }
  };

  const sendCard = () => {
    void sendMessage({
      type: 'card',
      content: {
        title: '《数据结构与算法》第 3 版',
        subtitle: '九成新 · 校内自提 · 包邮',
        price: '¥45.00',
        imageUrl: 'https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c?w=480&q=60',
        fields: [
          { label: '成色', value: '9 成新' },
          { label: '交易方式', value: '校内自提' },
        ],
        url: 'https://example.com/books/1001',
      },
    });
  };

  return (
    <div className="sidebar__actions">
      <button type="button" disabled={busy} onClick={() => void createConversation('c2c')}>
        + 商品会话
      </button>
      <button type="button" disabled={busy} onClick={() => void createConversation('c2ai')}>
        + AI 会话
      </button>
      <button type="button" className="ghost" disabled={!activeConversation} onClick={sendCard}>
        发卡片
      </button>
      <button
        type="button"
        className="ghost"
        title="新开标签页用另一个身份登录，验证实时收消息"
        onClick={() => window.open(`${window.location.pathname}?user=seller_1&name=卖家老王`, '_blank')}
      >
        开第二身份
      </button>
    </div>
  );
}

function LoginCard({
  onLogin,
  onError,
  error,
}: {
  onLogin: (session: Session) => void;
  onError: (message: string | null) => void;
  error: string | null;
}) {
  const [appId, setAppId] = useState(APP_ID);
  const [externalUserId, setExternalUserId] = useState('buyer_1');
  const [displayName, setDisplayName] = useState('买家小明');
  const [busy, setBusy] = useState(false);

  const presetUsers = useMemo(
    () => [
      { id: 'buyer_1', name: '买家小明' },
      { id: 'seller_1', name: '卖家老王' },
      { id: 'buyer_2', name: '买家小红' },
    ],
    [],
  );

  return (
    <form
      className="login-card"
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        void devLogin(appId.trim(), externalUserId.trim(), displayName.trim() || externalUserId.trim())
          .then(onLogin)
          .catch((err: Error) => onError(err.message))
          .finally(() => setBusy(false));
      }}
    >
      <h1>HChat Playground</h1>
      <p className="login-card__sub">用开发 Token 直接登录（无需 appSecret）</p>

      <div className="preset-row">
        {presetUsers.map((user) => (
          <button
            key={user.id}
            type="button"
            className={externalUserId === user.id ? 'chip is-active' : 'chip'}
            onClick={() => {
              setExternalUserId(user.id);
              setDisplayName(user.name);
            }}
          >
            {user.name}
          </button>
        ))}
      </div>

      <label>
        appId
        <input value={appId} onChange={(event) => setAppId(event.target.value)} required />
      </label>
      <label>
        用户 ID（externalUserId）
        <input value={externalUserId} onChange={(event) => setExternalUserId(event.target.value)} required />
      </label>
      <label>
        显示名
        <input value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
      </label>

      {error ? <div className="login-card__error">{error}</div> : null}

      <button type="submit" className="primary" disabled={busy}>
        {busy ? '登录中…' : '进入聊天'}
      </button>
    </form>
  );
}
