import type { Assignment, RosterMember, Submission, TrainingSession } from "./cloud";
import { esc, timeAgo } from "./format";
import { icon } from "./icons";
import { appPath } from "./router";
import type { CoachingQuestion } from "./coaching-hub";
import { incomingTimes, unreadCount } from "./coaching-unread";
import { getPreferences, PREFERENCES_EVENT, type PortalPreferences } from "./preferences";

export interface NotificationData {
  roster: RosterMember[];
  submissions: Submission[];
  assignments: Assignment[];
  sessionsByPlayer: Map<string, TrainingSession[]>;
  questions?: CoachingQuestion[] | null;
}
export interface CoachAlert { id: string; title: string; body: string; href: string; at: number; }

export function coachAlerts(data: NotificationData, preferences: PortalPreferences, now = Date.now()): CoachAlert[] {
  const alerts: CoachAlert[] = [];
  const name = (uid: string) => data.roster.find((member) => member.playerUid === uid)?.playerName || "A player";
  for (const question of data.questions ?? []) {
    if (unreadCount(question) > 0) {
      const at = Math.max(...incomingTimes(question));
      alerts.push({ id: `question-${question.id}-${at}`, title: "Unread player message", body: `${name(question.playerUid)} sent a training message.`, href: appPath("player", question.playerUid, "messages"), at });
    }
  }
  if (preferences.submissionAlerts) {
    for (const submission of data.submissions.filter((entry) => entry.reviewedAt === null)) {
      alerts.push({ id: `submission-${submission.sessionId}-${submission.submittedAt}`, title: "Session ready to review", body: `${name(submission.playerUid)} submitted a session.`, href: appPath("queue"), at: submission.submittedAt });
    }
  }
  if (preferences.deadlineReminders) {
    for (const assignment of data.assignments) {
      if (!assignment.dueAt || assignment.status === "Completed" || assignment.dueAt > now + 86_400_000 || assignment.dueAt < now - 7 * 86_400_000) continue;
      alerts.push({ id: `deadline-${assignment.id}-${assignment.dueAt}`, title: assignment.dueAt < now ? "Assignment overdue" : "Assignment due soon", body: `${assignment.title} · ${name(assignment.playerUid)}`, href: appPath("assignments"), at: Math.min(now, assignment.dueAt - 86_400_000) });
    }
  }
  if (preferences.trainingNotifications) {
    for (const [player, sessions] of data.sessionsByPlayer) {
      for (const session of sessions) {
        if (session.startedAt < now - 7 * 86_400_000) continue;
        alerts.push({ id: `training-${session.id}`, title: "Training session", body: `${name(player)} · ${session.skill} · ${session.repCount} reps`, href: appPath("player", player), at: session.startedAt });
      }
    }
  }
  return alerts.sort((a, b) => b.at - a.at).slice(0, 40);
}

export function notificationBell(): string {
  return `<div class="notification-wrap"><button class="icon-btn notification-bell" id="notification-bell" type="button" aria-label="Notifications" aria-expanded="false" aria-controls="notification-tray">${icon("bell")}<span id="notification-count" hidden></span></button><section class="notification-tray" id="notification-tray" aria-label="Your notifications" hidden><div class="notification-heading"><h2>Notifications</h2><button type="button" id="notifications-read">Mark all read</button></div><div id="notification-list"></div><a class="notification-settings-link" href="${appPath("account")}">Notification settings ${icon("arrow")}</a></section><span class="visually-hidden" id="notification-announcement" role="status" aria-live="polite"></span></div>`;
}

export function mountNotifications(root: HTMLElement, uid: string): { update: (data: NotificationData) => void; dispose: () => void } {
  const bell = root.querySelector<HTMLButtonElement>("#notification-bell")!;
  const tray = root.querySelector<HTMLElement>("#notification-tray")!;
  const list = root.querySelector<HTMLElement>("#notification-list")!;
  const count = root.querySelector<HTMLElement>("#notification-count")!;
  const read = root.querySelector<HTMLButtonElement>("#notifications-read")!;
  const key = `vl.coach.alerts.read.${uid}`;
  let seen = new Set<string>();
  try { const saved: unknown = JSON.parse(localStorage.getItem(key) ?? "[]"); if (Array.isArray(saved)) seen = new Set(saved.filter((id): id is string => typeof id === "string").slice(-250)); } catch { /* Fresh inbox. */ }
  let data: NotificationData | null = null;
  let lastUnread = -1;
  const store = () => { seen = new Set([...seen].slice(-250)); try { localStorage.setItem(key, JSON.stringify([...seen])); } catch { /* Keep session state. */ } };
  const close = (focus = false) => { tray.hidden = true; bell.setAttribute("aria-expanded", "false"); if (focus) bell.focus(); };
  const paint = () => {
    if (!data) return;
    const alerts = coachAlerts(data, getPreferences());
    const unread = alerts.filter((alert) => !seen.has(alert.id)).length;
    count.hidden = unread === 0;
    count.textContent = unread > 9 ? "9+" : String(unread);
    bell.setAttribute("aria-label", unread ? `Notifications, ${unread} unread` : "Notifications");
    if (lastUnread >= 0 && unread > lastUnread) root.querySelector("#notification-announcement")!.textContent = `${unread} unread notifications`;
    lastUnread = unread;
    read.disabled = unread === 0;
    list.innerHTML = alerts.length ? alerts.map((alert) => `<a class="notification-item${seen.has(alert.id) ? "" : " is-unread"}" href="${alert.href}" data-alert-id="${esc(alert.id)}"><span class="notification-dot" aria-hidden="true"></span><span><strong>${esc(alert.title)}</strong><span>${esc(alert.body)}</span><small>${esc(timeAgo(alert.at))}</small></span>${seen.has(alert.id) ? "" : '<span class="visually-hidden">Unread</span>'}</a>`).join("") : `<div class="notification-empty">${icon("bell")}<strong>You’re all caught up</strong><p>New team activity will appear here.</p></div>`;
  };
  bell.addEventListener("click", () => { const open = tray.hidden; tray.hidden = !open; bell.setAttribute("aria-expanded", String(open)); });
  read.addEventListener("click", () => { if (data) coachAlerts(data, getPreferences()).forEach((alert) => seen.add(alert.id)); store(); paint(); });
  list.addEventListener("click", (event) => { const link = (event.target as Element).closest<HTMLElement>("[data-alert-id]"); if (link?.dataset.alertId) { seen.add(link.dataset.alertId); store(); close(); paint(); } });
  const onClick = (event: MouseEvent) => { if (!(event.target as Element).closest(".notification-wrap")) close(); };
  const onKey = (event: KeyboardEvent) => { if (event.key === "Escape" && !tray.hidden) { event.preventDefault(); close(true); } };
  const onFocus = (event: FocusEvent) => { if (!(event.target as Element).closest(".notification-wrap")) close(); };
  document.addEventListener("click", onClick);
  document.addEventListener("keydown", onKey);
  document.addEventListener("focusin", onFocus);
  window.addEventListener(PREFERENCES_EVENT, paint);
  const timer = window.setInterval(paint, 60_000);
  return { update: (next) => { data = next; paint(); }, dispose: () => { window.clearInterval(timer); document.removeEventListener("click", onClick); document.removeEventListener("keydown", onKey); document.removeEventListener("focusin", onFocus); window.removeEventListener(PREFERENCES_EVENT, paint); } };
}
