import type { Message } from 'hchat-core-sdk';
import { useEffect, useMemo, useRef } from 'react';
import { useChat } from '../context';
import { avatarColor, formatDay, formatTime, initials, memberName, shouldShowTimestamp } from '../utils';
import { titleOf } from './ConversationList';

export interface ChatWindowProps {
  className?: string;
  showHeader?: boolean;
  emptyText?: string;
}

function compareId(a: string, b: string): number {
  try {
    const left = BigInt(a);
    const right = BigInt(b);
    return left === right ? 0 : left > right ? 1 : -1;
  } catch {
    return a.localeCompare(b);
  }
}

function MessageContent({ message }: { message: Message }) {
  const { content, type } = message;
  if (type === 'image' && content.url) {
    return (
      <a className="hchat-bubble__image" href={content.url} target="_blank" rel="noreferrer noopener">
        <img src={content.url} alt={content.fileName ?? '图片'} loading="lazy" />
      </a>
    );
  }
  if (type === 'file' && content.url) {
    return (
      <a className="hchat-bubble__file" href={content.url} target="_blank" rel="noreferrer noopener">
        <span className="hchat-bubble__file-icon" aria-hidden="true">
          📎
        </span>
        <span className="hchat-bubble__file-meta">
          <span className="hchat-bubble__file-name">{content.fileName ?? '文件'}</span>
          <span className="hchat-bubble__file-size">
            {content.size ? `${Math.max(1, Math.round(Number(content.size) / 1024))} KB` : '下载'}
          </span>
        </span>
      </a>
    );
  }
  if (type === 'card') {
    return (
      <div className="hchat-bubble__card">
        {content.imageUrl ? <img src={String(content.imageUrl)} alt={content.title ?? '卡片'} /> : null}
        <div className="hchat-bubble__card-body">
          <div className="hchat-bubble__card-title">{content.title ?? '卡片消息'}</div>
          {content.subtitle ? <div className="hchat-bubble__card-subtitle">{content.subtitle}</div> : null}
          {content.price ? <div className="hchat-bubble__card-price">{content.price}</div> : null}
          {Array.isArray(content.fields)
            ? content.fields.map((field) => (
                <div className="hchat-bubble__card-field" key={`${field.label}-${field.value}`}>
                  <span>{field.label}</span>
                  <span>{field.value}</span>
                </div>
              ))
            : null}
          {content.url ? (
            <a className="hchat-bubble__card-link" href={String(content.url)} target="_blank" rel="noreferrer noopener">
              查看详情
            </a>
          ) : null}
        </div>
      </div>
    );
  }
  return <div className="hchat-bubble__text">{content.text ?? ''}</div>;
}

/** 聊天窗口：消息气泡、时间分组、已读回执、撤回、AI 流式占位。 */
export function ChatWindow({ className = '', showHeader = true, emptyText = '选择一个会话开始聊天' }: ChatWindowProps) {
  const {
    activeId,
    activeConversation,
    messages,
    messagesLoading,
    hasMoreMessages,
    loadMoreMessages,
    currentUserId,
    streamingText,
    typingUserIds,
    connectionState,
    recallMessage,
  } = useChat();

  const listRef = useRef<HTMLDivElement>(null);
  const nearBottomRef = useRef(true);

  useEffect(() => {
    const element = listRef.current;
    if (!element) return;
    if (nearBottomRef.current) {
      element.scrollTop = element.scrollHeight;
    }
  }, [messages, streamingText, activeConversation?.id]);

  const typingNames = useMemo(() => {
    const members = activeConversation?.members ?? [];
    return typingUserIds
      .filter((id) => id !== currentUserId)
      .map((id) => memberName(members, id) || '对方')
      .join('、');
  }, [typingUserIds, activeConversation?.members, currentUserId]);

  const readMarker = useMemo(() => {
    const members = activeConversation?.members ?? [];
    const others = members.filter((member) => member.userId !== currentUserId);
    if (others.length === 0) return null;
    const minRead = others
      .map((member) => member.lastReadMessageId)
      .filter((id): id is string => Boolean(id))
      .sort(compareId)[0];
    return minRead ?? null;
  }, [activeConversation?.members, currentUserId]);

  if (!activeConversation) {
    return (
      <div className={`hchat-window ${className}`}>
        <div className="hchat-empty">{activeId ? '加载中…' : emptyText}</div>
      </div>
    );
  }

  const title = titleOf(activeConversation, currentUserId);
  const members = activeConversation.members ?? [];

  return (
    <div className={`hchat-window ${className}`}>
      {showHeader ? (
        <header className="hchat-window__header">
          <div className="hchat-window__title">{title}</div>
          <div className="hchat-window__meta">
            <span className={`hchat-dot hchat-dot--${connectionState}`} aria-hidden="true" />
            <span>
              {connectionState === 'authenticated'
                ? typingNames
                  ? `${typingNames} 正在输入…`
                  : '在线'
                : connectionState === 'reconnecting'
                  ? '重连中…'
                  : '离线'}
            </span>
          </div>
        </header>
      ) : null}

      <div
        className="hchat-window__messages"
        ref={listRef}
        onScroll={(event) => {
          const element = event.currentTarget;
          nearBottomRef.current = element.scrollHeight - element.scrollTop - element.clientHeight < 120;
        }}
      >
        {hasMoreMessages ? (
          <button type="button" className="hchat-load-more" onClick={() => void loadMoreMessages()}>
            加载更多
          </button>
        ) : null}

        {messagesLoading && messages.length === 0 ? <div className="hchat-empty">加载中…</div> : null}

        {!messagesLoading && messages.length === 0 && streamingText === null ? (
          <div className="hchat-empty">还没有消息，发第一条吧</div>
        ) : null}

        {messages.map((message, index) => {
          const previous = messages[index - 1];
          const showDay = !previous || formatDay(previous.createdAt) !== formatDay(message.createdAt);
          const own = message.senderId != null && message.senderId === currentUserId;
          const name = message.senderType === 'ai' ? 'AI 助手' : memberName(members, message.senderId);

          if (message.type === 'system') {
            return (
              <div key={message.id} className="hchat-system">
                {showDay ? <div className="hchat-day">{formatDay(message.createdAt)}</div> : null}
                <span>{message.content.text}</span>
              </div>
            );
          }

          return (
            <div key={message.id}>
              {showDay ? <div className="hchat-day">{formatDay(message.createdAt)}</div> : null}
              <div className={`hchat-row${own ? ' hchat-row--own' : ''}`}>
                {!own ? (
                  <span
                    className="hchat-avatar hchat-avatar--sm"
                    style={{ backgroundColor: avatarColor(message.senderId ?? message.id) }}
                    aria-hidden="true"
                  >
                    {initials(name || '?')}
                  </span>
                ) : null}
                <div className="hchat-row__body">
                  {!own && name ? <div className="hchat-row__name">{name}</div> : null}
                  {message.status === 'recalled' ? (
                    <div className="hchat-bubble hchat-bubble--recalled">消息已撤回</div>
                  ) : (
                    <div className={`hchat-bubble${own ? ' hchat-bubble--own' : ''}`}>
                      <MessageContent message={message} />
                    </div>
                  )}
                  <div className="hchat-row__footer">
                    {shouldShowTimestamp(previous, message) ? (
                      <span className="hchat-row__time">{formatTime(message.createdAt)}</span>
                    ) : null}
                    {own && message.status === 'sent' ? (
                      <button
                        type="button"
                        className="hchat-link-button"
                        onClick={() => void recallMessage(message.id)}
                      >
                        撤回
                      </button>
                    ) : null}
                    {own && readMarker && compareId(readMarker, message.id) >= 0 ? (
                      <span className="hchat-row__read">已读</span>
                    ) : null}
                  </div>
                </div>
              </div>
            </div>
          );
        })}

        {streamingText !== null ? (
          <div className="hchat-row">
            <span className="hchat-avatar hchat-avatar--sm" style={{ backgroundColor: avatarColor('ai') }} aria-hidden="true">
              AI
            </span>
            <div className="hchat-row__body">
              <div className="hchat-row__name">AI 助手</div>
              <div className="hchat-bubble hchat-bubble--streaming">
                {streamingText || <span className="hchat-typing-dots">正在思考…</span>}
              </div>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  );
}
