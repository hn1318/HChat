import type { ConversationMember, Message } from 'hchat-core-sdk';

/** HH:mm（本地时区）。 */
export function formatTime(iso?: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

/** 今天 / 昨天 / M月D日。 */
export function formatDay(iso?: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const target = new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
  const dayDiff = Math.round((startOfToday - target) / 86_400_000);
  if (dayDiff === 0) return '今天';
  if (dayDiff === 1) return '昨天';
  if (date.getFullYear() === now.getFullYear()) return `${date.getMonth() + 1}月${date.getDate()}日`;
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`;
}

/** 消息之间超过 5 分钟才显示时间，避免视觉噪音。 */
export function shouldShowTimestamp(prev: Message | undefined, current: Message): boolean {
  if (!prev) return true;
  const prevTime = new Date(prev.createdAt).getTime();
  const currentTime = new Date(current.createdAt).getTime();
  return currentTime - prevTime > 5 * 60 * 1000;
}

export function memberName(members: ConversationMember[] | null | undefined, senderId?: string | null): string {
  if (!senderId) return '';
  const member = members?.find((item) => item.userId === senderId);
  return member?.displayName ?? member?.externalUserId ?? '';
}

export function initials(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return '?';
  return trimmed.slice(0, 1).toUpperCase();
}

/** 生成稳定的头像底色。 */
export function avatarColor(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) {
    hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  }
  const palette = ['#5b8def', '#31a37a', '#c2703d', '#8e6bd8', '#c2506e', '#3f9bb0'];
  return palette[Math.abs(hash) % palette.length] ?? '#5b8def';
}
