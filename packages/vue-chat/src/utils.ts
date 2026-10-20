import type { ConversationMember, Message } from 'hchat-core-sdk';

export function formatTime(iso?: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
}

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

export function shouldShowTimestamp(prev: Message | undefined, current: Message): boolean {
  if (!prev) return true;
  return new Date(current.createdAt).getTime() - new Date(prev.createdAt).getTime() > 5 * 60 * 1000;
}

export function memberName(members: ConversationMember[] | null | undefined, senderId?: string | null): string {
  if (!senderId) return '';
  const member = members?.find((item) => item.userId === senderId);
  return member?.displayName ?? member?.externalUserId ?? '';
}

export function initials(name: string): string {
  const trimmed = name.trim();
  return trimmed ? trimmed.slice(0, 1).toUpperCase() : '?';
}

export function avatarColor(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i += 1) {
    hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  }
  const palette = ['#5b8def', '#31a37a', '#c2703d', '#8e6bd8', '#c2506e', '#3f9bb0'];
  return palette[Math.abs(hash) % palette.length] ?? '#5b8def';
}

export function compareId(a: string, b: string): number {
  try {
    const left = BigInt(a);
    const right = BigInt(b);
    return left === right ? 0 : left > right ? 1 : -1;
  } catch {
    return a.localeCompare(b);
  }
}
