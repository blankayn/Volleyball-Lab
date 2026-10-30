import type { CoachingQuestion } from "./coaching-hub";

export const incomingTimes = (q: CoachingQuestion): number[] =>
  [q.createdAt, q.followUp1?.createdAt ?? 0, q.followUp2?.createdAt ?? 0].filter(at => at > 0);

export const unreadCount = (q: CoachingQuestion): number =>
  incomingTimes(q).filter(at => at > (q.coachReadAt ?? 0)).length;

export const playerUnreadCount = (questions: CoachingQuestion[] | null, playerUid: string): number =>
  (questions ?? []).filter(q => q.playerUid === playerUid).reduce((total, q) => total + unreadCount(q), 0);

export const messageBadge = (count: number): string => count > 0
  ? `<span class="message-badge" aria-label="${count} unread messages">${count > 99 ? "99+" : count}</span>` : "";
