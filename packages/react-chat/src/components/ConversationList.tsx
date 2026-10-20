import type { Conversation } from 'hchat-core-sdk';
import { useMemo, useState } from 'react';
import { useChat } from '../context';
import { avatarColor, formatTime, initials } from '../utils';

export interface ConversationListProps {
  className?: string;
  emptyText?: string;
  showSearch?: boolean;
  onSelect?: (conversation: Conversation) => void;
}

function titleOf(conversation: Conversation, currentUserId: string | null): string {
  if (conversation.title) return conversation.title;
  const other = conversation.members?.find((member) => member.userId !== currentUserId);
  if (other) return other.displayName ?? other.externalUserId ?? '会话';
  if (conversation.type === 'c2ai') return 'AI 助手';
  return `会话 ${conversation.id.slice(-6)}`;
}

/** 会话列表：未读红点、最后一条消息摘要、时间与搜索。 */
export function ConversationList({
  className = '',
  emptyText = '还没有会话',
  showSearch = true,
  onSelect,
}: ConversationListProps) {
  const { conversations, activeConversation, openConversation, conversationsLoading, currentUserId } = useChat();
  const [keyword, setKeyword] = useState('');

  const filtered = useMemo(() => {
    const trimmed = keyword.trim().toLowerCase();
    if (!trimmed) return conversations;
    return conversations.filter((conversation) => {
      const title = titleOf(conversation, currentUserId).toLowerCase();
      const preview = (conversation.lastMessagePreview ?? '').toLowerCase();
      return title.includes(trimmed) || preview.includes(trimmed);
    });
  }, [conversations, keyword, currentUserId]);

  return (
    <div className={`hchat-conversations ${className}`}>
      {showSearch ? (
        <div className="hchat-conversations__search">
          <input
            type="search"
            value={keyword}
            placeholder="搜索会话"
            aria-label="搜索会话"
            onChange={(event) => setKeyword(event.target.value)}
          />
        </div>
      ) : null}

      <div className="hchat-conversations__list" role="list">
        {conversationsLoading && conversations.length === 0 ? (
          <div className="hchat-empty">{emptyText === '还没有会话' ? '加载中…' : emptyText}</div>
        ) : null}

        {!conversationsLoading && filtered.length === 0 ? (
          <div className="hchat-empty">{emptyText}</div>
        ) : null}

        {filtered.map((conversation) => {
          const title = titleOf(conversation, currentUserId);
          const active = conversation.id === activeConversation?.id;
          const unread = conversation.unreadCount;
          return (
            <button
              key={conversation.id}
              type="button"
              role="listitem"
              className={`hchat-conversation${active ? ' is-active' : ''}`}
              onClick={() => {
                openConversation(conversation.id);
                onSelect?.(conversation);
              }}
            >
              <span
                className="hchat-avatar"
                style={{ backgroundColor: avatarColor(conversation.id) }}
                aria-hidden="true"
              >
                {initials(title)}
              </span>
              <span className="hchat-conversation__body">
                <span className="hchat-conversation__top">
                  <span className="hchat-conversation__title">{title}</span>
                  <span className="hchat-conversation__time">{formatTime(conversation.lastMessageAt)}</span>
                </span>
                <span className="hchat-conversation__bottom">
                  <span className="hchat-conversation__preview">
                    {conversation.lastMessagePreview ?? '暂无消息'}
                  </span>
                  {unread > 0 ? <span className="hchat-badge">{unread > 99 ? '99+' : unread}</span> : null}
                </span>
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

export { titleOf };
