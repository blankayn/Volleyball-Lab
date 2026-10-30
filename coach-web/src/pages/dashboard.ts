import { auth } from "../lib/firebase";
import { icon } from "../lib/icons";
import {
  deleteAssignment,
  getAssignments,
  getAssignmentsForPlayer,
  getNotesForPlayer,
  getProfile,
  getRoster,
  getSessionsFor,
  getSubmissions,
  postNote,
  queueQuery,
  rosterQuery,
  saveAssignment,
  assignmentsQuery,
  sharedSessionsQuery,
  setMemberStatus,
  subscribe,
  uid as newId,
  type Assignment,
  type CoachNote,
  type RosterMember,
  type Skill,
  type Submission,
  type TrainingSession,
} from "../lib/cloud";
import { cueLabel, cuesFor, formatMeasured, formatTarget, SKILL_LABEL } from "../lib/cues";
import { dueLabel, esc, fullDate, initials, timeAgo } from "../lib/format";
import { appPath, go } from "../lib/router";
import { avatar, emptyState, errorCard, iconChip, scoreBar } from "../lib/ui";
import { hydrateAccountPreferences, renderAccount, wireAccount } from "./account";
import { getPreferences } from "../lib/preferences";
import { mountNotifications, notificationBell } from "../lib/notifications";
import { sessionExplanationPanel, wireSessionExplanations } from "../lib/session-explanation";
import { wireCoachExperience } from "../lib/coach-experience";
import { messageBadge, playerUnreadCount, unreadCount } from "../lib/coaching-unread";
import { getCoachQuestions, questionsQuery, renderCoachQuestions, wireCoachingHub, type CoachingQuestion } from "../lib/coaching-hub";

type Tab = "overview" | "queue" | "roster" | "assignments" | "account" | "player";

interface DashData {
  roster: RosterMember[];
  submissions: Submission[];
  assignments: Assignment[];
  sessionsByPlayer: Map<string, TrainingSession[]>;
  profileName: string;
  profileTeam: string;
  questions: CoachingQuestion[] | null;
}

let unsubscribers: (() => void)[] = [];
const expandedSessions = new Set<string>();
const expandedConversations = new Set<string>();
const noteDrafts = new Map<string, string>();
let draftAccount = "";
let disposeExperience: (() => void) | null = null;
let statusTimer: ReturnType<typeof setTimeout>;
let disposeSidebar: (() => void) | null = null;
let disposeAccount: (() => void) | null = null;
let disposeNotifications: (() => void) | null = null;
let disposeExplanations: (() => void) | null = null;
let disposeHub: (() => void) | null = null;

let dashboardVersion = 0;
export function disposeDashboard(): void {
  disposeHub?.();
  disposeHub = null;
  dashboardVersion++;
  clearTimeout(statusTimer);
  stopLive();
  disposeAccount?.();
  disposeAccount = null;
  disposeNotifications?.();
  disposeNotifications = null;
  disposeExplanations?.();
  disposeExplanations = null;
  disposeSidebar?.();
  disposeSidebar = null;
  disposeExperience?.();
  disposeExperience = null;
  expandedSessions.clear();
  expandedConversations.clear();
}

function stopLive(): void {
  unsubscribers.forEach((u) => u());
  unsubscribers = [];
}

function activeRoster(roster: RosterMember[]): RosterMember[] {
  return roster.filter((m) => m.status === "Active");
}

function memberName(roster: RosterMember[], playerUid: string): string {
  return roster.find((m) => m.playerUid === playerUid)?.playerName || "Player";
}

function meanForm(sessions: TrainingSession[]): number | null {
  const scores = sessions.map((s) => s.formScore).filter((s): s is number => s !== null);
  if (scores.length === 0) return null;
  return Math.round(scores.reduce((a, b) => a + b, 0) / scores.length);
}

/** Lowest-mean cue with ≥3 measured reps — the app's MIN_REPS rule, mirrored. */
function weakestCue(sessions: TrainingSession[]): { cueId: string; mean: number } | null {
  const byCue = new Map<string, number[]>();
  for (const s of sessions) {
    for (const r of s.reps) {
      for (const c of r.cueScores) {
        if (c.score === null) continue;
        const list = byCue.get(c.cueId) ?? [];
        list.push(c.score);
        byCue.set(c.cueId, list);
      }
    }
  }
  let worst: { cueId: string; mean: number } | null = null;
  for (const [cueId, scores] of byCue) {
    if (scores.length < 3) continue;
    const mean = scores.reduce((a, b) => a + b, 0) / scores.length;
    if (!worst || mean < worst.mean) worst = { cueId, mean };
  }
  return worst;
}

function perSkillMeans(sessions: TrainingSession[]): { skill: Skill; mean: number | null }[] {
  const skills: Skill[] = ["Serving", "Receiving", "Setting", "Spiking", "Blocking"];
  return skills.map((skill) => {
    const scores = sessions
      .filter((s) => s.skill === skill)
      .map((s) => s.formScore)
      .filter((s): s is number => s !== null);
    return {
      skill,
      mean: scores.length > 0 ? Math.round(scores.reduce((a, b) => a + b, 0) / scores.length) : null,
    };
  });
}

async function loadData(coachUid: string): Promise<DashData> {
  const [roster, submissions, assignments, profile, questions] = await Promise.all([
    getRoster(coachUid),
    getSubmissions(coachUid),
    getAssignments(coachUid),
    getProfile(coachUid),
    getCoachQuestions(coachUid).catch(() => null),
  ]);
  const sessionsByPlayer = new Map<string, TrainingSession[]>();
  await Promise.all(
    activeRoster(roster).map(async (m) => {
      sessionsByPlayer.set(m.playerUid, await getSessionsFor(m.playerUid, coachUid));
    }),
  );
  return { roster, submissions, assignments, sessionsByPlayer, profileName: profile.name, profileTeam: profile.teamName, questions };
}

const PAGE_LABELS: Record<Tab, string> = {
  overview: "Overview",
  queue: "Review queue",
  roster: "Player roster",
  assignments: "Assignments",
  account: "Account",
  player: "Player profile",
};

function navIcon(paths: string): string {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" class="ui-icon" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${paths}</svg>`;
}

function updateCoachAvatars(root: HTMLElement, name: string): void {
  const photo = getPreferences().photo;
  const content = photo ? `<img src="${esc(photo)}" alt="" />` : esc(initials(name));
  root.querySelectorAll("#workspace-avatar, #sidebar-user-avatar").forEach((avatar) => {
    avatar.innerHTML = content;
  });
}

function sidebarLinks(
  tab: Tab,
  playerUid: string | null,
  pending = 0,
  requests = 0,
): string {
  const link = (id: Tab, label: string, icon: string, href = appPath(id), badge = 0) =>
    `<a class="sidebar-link${tab === id ? " active" : ""}" href="${href}"${tab === id ? ' aria-current="page"' : ""} data-label="${esc(label)}" title="${esc(label)}">${navIcon(icon)}<span class="sidebar-label">${esc(label)}</span>${badge > 0 ? `<span class="sidebar-badge">${badge > 99 ? "99+" : badge}</span>` : ""}</a>`;
  return `${link("overview", PAGE_LABELS.overview, '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>')}
    ${link("queue", PAGE_LABELS.queue, '<rect x="4" y="3" width="16" height="18" rx="2"/><path d="m8 9 1.5 1.5L12 8M14 9h3M8 16h9"/>', appPath("queue"), pending)}
    ${link("roster", PAGE_LABELS.roster, '<circle cx="9" cy="8" r="3"/><path d="M3 21v-3a6 6 0 0 1 12 0v3M16 5a3 3 0 0 1 0 6M18 14a5 5 0 0 1 3 4v3"/>', appPath("roster"), requests)}
    ${link("assignments", PAGE_LABELS.assignments, '<rect x="5" y="5" width="14" height="16" rx="2"/><rect x="9" y="2" width="6" height="5" rx="1"/><path d="M9 12h6M9 16h4"/>')}
    ${link("account", PAGE_LABELS.account, '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>')}
    ${playerUid ? `<div class="sidebar-context"><p class="sidebar-label sidebar-context-label">Selected player</p>${link("player", memberShort(playerUid), '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>', appPath("player", playerUid))}</div>` : ""}`;
}

function wireSidebar(root: HTMLElement): void {
  const shell = root.querySelector<HTMLElement>("#app-shell");
  const sidebar = root.querySelector<HTMLElement>("#dashboard-sidebar");
  const scrim = root.querySelector<HTMLElement>("#sidebar-scrim");
  const topToggle = root.querySelector<HTMLButtonElement>("#sidebar-toggle");
  const collapseToggle = root.querySelector<HTMLButtonElement>("#sidebar-collapse");
  const closeToggle = root.querySelector<HTMLButtonElement>("#sidebar-close");
  if (!shell || !sidebar || !topToggle) return;

  const mobile = window.matchMedia("(max-width: 960px)");
  const storageKey = "vl.coach.sidebar.collapsed";
  const isMobile = () => mobile.matches;
  const savedCollapsed = () => {
    try {
      return localStorage.getItem(storageKey) === "1";
    } catch {
      return false;
    }
  };

  const setCollapsed = (collapsed: boolean, persist = true) => {
    shell.classList.toggle("is-collapsed", collapsed);
    topToggle.setAttribute("aria-expanded", String(!collapsed));
    if (collapseToggle) {
      const action = collapsed ? "Expand sidebar" : "Collapse sidebar";
      collapseToggle.setAttribute("aria-label", action);
      collapseToggle.title = action;
      collapseToggle.innerHTML = navIcon(collapsed ? '<path d="m10 7 5 5-5 5"/>' : '<path d="m14 7-5 5 5 5"/>');
    }
    if (persist) {
      try {
        localStorage.setItem(storageKey, collapsed ? "1" : "0");
      } catch {
        /* storage unavailable — the toggle still works this session */
      }
    }
  };

  const setDrawer = (open: boolean) => {
    shell.classList.toggle("is-mobile-open", open);
    if (scrim) scrim.hidden = !open;
    topToggle.setAttribute("aria-expanded", String(open));
    document.body.style.overflow = open ? "hidden" : "";
    sidebar.inert = isMobile() && !open;
    sidebar.setAttribute("aria-hidden", String(isMobile() && !open));
    const main = shell.querySelector<HTMLElement>(".app-main");
    if (main) main.inert = open;
    if (!open && sidebar.contains(document.activeElement)) topToggle.focus();
    if (open) sidebar.querySelector<HTMLAnchorElement>(".sidebar-nav a")?.focus();
  };

  if (isMobile()) setDrawer(false);
  else setCollapsed(savedCollapsed(), false);

  topToggle.addEventListener("click", () => {
    if (isMobile()) setDrawer(!shell.classList.contains("is-mobile-open"));
    else setCollapsed(!shell.classList.contains("is-collapsed"));
  });
  collapseToggle?.addEventListener("click", () => setCollapsed(!shell.classList.contains("is-collapsed")));
  closeToggle?.addEventListener("click", () => setDrawer(false));
  scrim?.addEventListener("click", () => setDrawer(false));
  sidebar.addEventListener("click", (event) => {
    if ((event.target as Element).closest("a")) setDrawer(false);
  });

  const onKeydown = (event: KeyboardEvent) => {
    if (event.key === "Escape" && isMobile() && shell.classList.contains("is-mobile-open")) setDrawer(false);
    if (event.key === "Tab" && isMobile() && shell.classList.contains("is-mobile-open")) {
      const controls = Array.from(sidebar.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')).filter(element => element.getClientRects().length > 0);
      const first = controls[0], last = controls[controls.length - 1];
      if (event.shiftKey && (document.activeElement === first || !sidebar.contains(document.activeElement))) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  };
  const onBreakpoint = () => {
    setDrawer(false);
    shell.classList.remove("is-collapsed");
    if (!isMobile()) setCollapsed(savedCollapsed(), false);
  };
  document.addEventListener("keydown", onKeydown);
  mobile.addEventListener("change", onBreakpoint);

  disposeSidebar = () => {
    document.removeEventListener("keydown", onKeydown);
    mobile.removeEventListener("change", onBreakpoint);
    document.body.style.overflow = "";
  };
}

let nameCache = new Map<string, string>();
function memberShort(playerUid: string): string {
  return nameCache.get(playerUid)?.split(" ")[0] ?? "Player";
}

export async function renderDashboard(
  root: HTMLElement,
  coachUid: string,
  tab: string,
  playerUid: string | null,
  sessionId: string | null = null,
): Promise<void> {
  stopLive();
  if (coachUid !== draftAccount) { noteDrafts.clear(); draftAccount = coachUid; }
  if (sessionId === "messages" && playerUid) expandedConversations.add(playerUid);
  else if (sessionId) expandedSessions.add(sessionId);
  const version = ++dashboardVersion;
  const activeTab = (["overview", "queue", "roster", "assignments", "account", "player"] as Tab[]).includes(tab as Tab)
    ? (tab as Tab)
    : "overview";

  root.innerHTML = `<div class="app-shell" id="app-shell">
    <aside class="app-sidebar" id="dashboard-sidebar" aria-label="Dashboard sidebar">
      <div class="sidebar-brand">
        <a class="sidebar-logo" href="${appPath("overview")}" aria-label="Volleyball Lab dashboard">
          <img src="/assets/volleyball-lab-logo.png" alt="" />
          <span class="sidebar-label"><strong>Volleyball Lab</strong><small>Coach portal</small></span>
        </a>
        <button class="sidebar-collapse" id="sidebar-collapse" type="button" aria-label="Collapse sidebar" title="Collapse sidebar">${navIcon('<path d="m14 7-5 5 5 5"/>')}</button>
        <button class="icon-btn sidebar-mobile-close" id="sidebar-close" type="button" aria-label="Close navigation">${navIcon('<path d="m6 6 12 12M18 6 6 18"/>')}</button>
      </div>
      <div class="sidebar-workspace">
        <span class="workspace-avatar" id="workspace-avatar">VL</span>
        <span class="sidebar-label"><strong id="workspace-name">Coach workspace</strong><small>Team dashboard</small></span>
      </div>
      <p class="sidebar-label sidebar-nav-label">Menu</p>
      <nav class="sidebar-nav" aria-label="Coach dashboard pages">${sidebarLinks(activeTab, playerUid)}</nav>
      <div class="sidebar-footer">
        <div class="sidebar-user">
          <span class="workspace-avatar" id="sidebar-user-avatar">C</span>
          <span class="sidebar-label"><strong id="sidebar-user-name">Coach</strong><small>Signed in</small></span>
        </div>
        <button class="sidebar-signout" type="button" data-action="sign-out" data-label="Sign out" title="Sign out" aria-label="Sign out">${navIcon('<path d="M9 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M14 8l4 4-4 4M8 12h13"/>')}<span class="sidebar-label">Sign out</span></button>
      </div>
    </aside>
    <div class="sidebar-scrim" id="sidebar-scrim" hidden></div>
    <div class="app-main">
      <header class="app-topbar">
        <button class="icon-btn" id="sidebar-toggle" type="button" aria-controls="dashboard-sidebar" aria-expanded="true" aria-label="Toggle navigation">${navIcon('<path d="M4 6h16M4 12h16M4 18h16"/>')}</button>
        <div class="app-topbar-title">
          <span class="app-crumb">Dashboard</span>
          <div class="app-title-line"><h1 id="page-heading" tabindex="-1">${PAGE_LABELS[activeTab]}</h1><span class="dash-team-name" id="team-name">Your team</span></div>
        </div>
        <div class="app-topbar-actions">
          <span class="sync-pill"><i></i>Online</span>
          ${notificationBell()}
          <a class="btn btn-primary btn-sm topbar-cta" href="${appPath("assignments")}" aria-label="Set a new drill">${navIcon('<path d="M12 5v14M5 12h14"/>')}<span>New drill</span></a>
        </div>
      </header>
      <main class="app-content" id="dashboard-content">
        <div id="action-status" role="status" aria-live="polite"></div>
        <div id="dash-body"><div class="coach-loading" role="status" aria-label="Loading your team"><div></div><div></div><div></div></div></div>
      </main>
    </div>
  </div>`;
  document.title = `${PAGE_LABELS[activeTab]} — Volleyball Lab Coach Portal`;
  wireSidebar(root);
  wireSignOut(root);
  const notifications = mountNotifications(root, coachUid);
  disposeNotifications = notifications.dispose;
  await hydrateAccountPreferences(coachUid);
  if (version !== dashboardVersion) return;

  const body = root.querySelector<HTMLElement>("#dash-body");
  if (!body) return;

  let request = 0;
  let dirty = false;
  let hasRendered = false;
  const filterQueries = new Map<string, string>();
  const editing = () => dirty || (document.activeElement instanceof HTMLElement
    && body.contains(document.activeElement) && document.activeElement.matches("input, textarea, select"));
  const restoreDrafts = () => body.querySelectorAll<HTMLTextAreaElement>("[data-note-draft]").forEach(field => {
    if (field.dataset.draftRestored) return;
    field.dataset.draftRestored = "true";
    field.value = noteDrafts.get(field.dataset.noteDraft!) ?? "";
    const counter = body.querySelector(`[data-note-count="${CSS.escape(field.dataset.noteDraft!)}"]`);
    if (counter) counter.textContent = `${field.value.length}/800`;
    const send = body.querySelector<HTMLButtonElement>(`[data-action="send-note"][data-session="${CSS.escape(field.dataset.noteDraft!)}"]`);
    if (send) send.disabled = !field.value.trim();
  });
  body.addEventListener("input", (event) => {
    const target = event.target as HTMLInputElement | HTMLTextAreaElement;
    if (target.matches("[data-coach-search]")) { filterQueries.set(target.getAttribute("aria-label") ?? "", target.value); return; }
    dirty = true;
    if (target.dataset.noteDraft) {
      noteDrafts.set(target.dataset.noteDraft, target.value);
      const count = body.querySelector(`[data-note-count="${CSS.escape(target.dataset.noteDraft)}"]`);
      if (count) count.textContent = `${target.value.length}/800`;
      const send = body.querySelector<HTMLButtonElement>(`[data-action="send-note"][data-session="${CSS.escape(target.dataset.noteDraft)}"]`);
      if (send) send.disabled = !target.value.trim();
    }
  });
  body.addEventListener("change", (event) => { if (!(event.target as Element).matches("[data-coach-search]")) dirty = true; });
  body.addEventListener("toggle", (event) => {
    const details = event.target;
    if (details instanceof HTMLDetailsElement && details.dataset.messageThread) {
      if (details.open) expandedConversations.add(details.dataset.messageThread);
      else expandedConversations.delete(details.dataset.messageThread);
    }
    if (details instanceof HTMLDetailsElement && details.dataset.sessionDetails) {
      restoreDrafts();
      if (details.open) expandedSessions.add(details.dataset.sessionDetails);
      else expandedSessions.delete(details.dataset.sessionDetails);
    }
  }, true);
  const render = async (live = false) => {
    const attempt = ++request;
    try {
      const data = await loadData(coachUid);
      if (version !== dashboardVersion || attempt !== request || !body.isConnected) return;
      notifications.update(data);
      nameCache = new Map(data.roster.map((m) => [m.playerUid, m.playerName || "Player"]));
      // Refresh badges and the selected-player link without replacing the shell.
      const nav = root.querySelector(".sidebar-nav");
      if (nav) {
        nav.innerHTML = sidebarLinks(
          activeTab,
          playerUid,
          data.submissions.filter((s) => s.reviewedAt === null).length,
          data.roster.filter((m) => m.status === "Pending").length + (data.questions?.reduce((total, q) => total + unreadCount(q), 0) ?? 0),
        );
      }
      if (live && editing()) return;
      const html = await viewFor(activeTab, coachUid, data, playerUid);
      if (version !== dashboardVersion || attempt !== request || !body.isConnected || (live && editing())) return;
      disposeAccount?.();
      disposeAccount = null;
      disposeExplanations?.();
      disposeExplanations = null;
      disposeExperience?.();
      disposeHub?.();
      body.innerHTML = html;
      body.querySelectorAll<HTMLInputElement>("[data-coach-search]").forEach(field => {
        field.value = filterQueries.get(field.getAttribute("aria-label") ?? "") ?? "";
      });
      const firstRender = !hasRendered;
      disposeExperience = wireCoachExperience(body, firstRender);
      restoreDrafts();
      hasRendered = true;
      if (firstRender && sessionId && sessionId !== "messages") requestAnimationFrame(() => {
        if (version !== dashboardVersion || !body.isConnected) return;
        const selected = body.querySelector<HTMLDetailsElement>(`[data-session-details="${CSS.escape(sessionId)}"]`);
        selected?.scrollIntoView({ block: "start", behavior: "auto" });
        selected?.querySelector<HTMLElement>("summary")?.focus({ preventScroll: true });
      });
      dirty = false;
      const coachName = data.profileName || "Coach";
      const teamName = root.querySelector(".dash-team-name");
      if (teamName) teamName.textContent = data.profileTeam || (data.profileName ? `${data.profileName}’s team` : "Your team");
      const sidebarUserName = root.querySelector("#sidebar-user-name");
      if (sidebarUserName) sidebarUserName.textContent = coachName;
      const workspaceName = root.querySelector("#workspace-name");
      if (workspaceName) workspaceName.textContent = data.profileTeam || (data.profileName ? `${data.profileName}’s workspace` : "Coach workspace");
      updateCoachAvatars(root, coachName);
      if (activeTab === "player" && playerUid) {
        const heading = root.querySelector("#page-heading");
        if (heading) heading.textContent = nameCache.get(playerUid) || PAGE_LABELS.player;
      }
      wireActions(root, coachUid, () => void render());
      disposeHub = wireCoachingHub(root, coachUid, () => void render());
      disposeExplanations = wireSessionExplanations(root);
      disposeAccount = wireAccount(root, coachUid, () => {
        void getProfile(coachUid).then((profile) => {
          if (version !== dashboardVersion) return;
          const name = profile.name || "Coach";
          if (sidebarUserName) sidebarUserName.textContent = name;
          if (workspaceName) workspaceName.textContent = profile.teamName || `${name}’s workspace`;
          if (teamName) teamName.textContent = profile.teamName || `${name}’s team`;
          updateCoachAvatars(root, name);
        }).catch(() => undefined);
      });
    } catch (e) {
      console.error(e);
      if (version !== dashboardVersion || attempt !== request || !body.isConnected) return;
      if (hasRendered) return;
      body.innerHTML = errorCard(
        "Could not load your team. Check your connection and try again.",
        "reload",
      );
      body.querySelector("[data-retry]")?.addEventListener("click", () => void render());
    }
  };

  // Live queue + roster counts; the body re-renders on change.
  let liveRefresh: ReturnType<typeof setTimeout>;
  const scheduleRefresh = () => { clearTimeout(liveRefresh); liveRefresh = setTimeout(() => void render(true), 160); };
  unsubscribers = [
    subscribe(queueQuery(coachUid), scheduleRefresh, () => undefined),
    subscribe(rosterQuery(coachUid), scheduleRefresh, () => undefined),
    subscribe(assignmentsQuery(coachUid), scheduleRefresh, () => undefined),
    subscribe(sharedSessionsQuery(coachUid), scheduleRefresh, () => undefined),
    subscribe(questionsQuery(coachUid), scheduleRefresh, () => undefined),
    () => clearTimeout(liveRefresh),
  ];
  await render();
}

async function viewFor(
  tab: Tab,
  coachUid: string,
  data: DashData,
  playerUid: string | null,
): Promise<string> {
  switch (tab) {
    case "queue":
      return viewQueue(data, playerUid);
    case "roster":
      return viewRoster(data);
    case "assignments":
      return viewAssignments(data, null);
    case "account":
      return renderAccount(coachUid);
    case "player":
      return playerUid ? viewPlayer(coachUid, data, playerUid) : viewOverview(data);
    default:
      return viewOverview(data);
  }
}

// ---- Overview ---------------------------------------------------------------

function greeting(date = new Date()): string {
  const hour = date.getHours();
  if (hour < 12) return "Good morning";
  if (hour < 18) return "Good afternoon";
  return "Good evening";
}

function statCard(
  icon: string,
  tone: "rust" | "indigo" | "green" | "violet",
  value: string | number,
  label: string,
  note: string,
  noteTone: "up" | "warn" | "flat" = "flat",
): string {
  return `<article class="stat-card">
    <div class="stat-card-top"><span class="stat-icon icon-${tone}">${navIcon(icon)}</span><span class="stat-note${noteTone === "flat" ? "" : ` ${noteTone}`}">${esc(note)}</span></div>
    <b>${value}</b><span class="stat-label">${esc(label)}</span>
  </article>`;
}

function activityBars(sessions: TrainingSession[]): string {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const days = Array.from({ length: 7 }, (_, index) => {
    const day = new Date(today);
    day.setDate(today.getDate() - (6 - index));
    const start = day.getTime();
    return {
      label: day.toLocaleDateString(undefined, { weekday: "short" }),
      count: sessions.filter((s) => s.startedAt >= start && s.startedAt < start + 86_400_000).length,
      today: index === 6,
    };
  });
  const max = Math.max(1, ...days.map((d) => d.count));
  return days.map((d) => `<div class="activity-col${d.today ? " is-today" : ""}" title="${d.count} session${d.count === 1 ? "" : "s"}">
      <span class="activity-count">${d.count || ""}</span>
      <span class="activity-track"><i style="height:${Math.max(4, Math.round((d.count / max) * 100))}%"></i></span>
      <small>${d.label}</small>
    </div>`).join("");
}

function skillForm(sessions: TrainingSession[]): { html: string; hasData: boolean } {
  const skills = perSkillMeans(sessions);
  return {
    hasData: skills.some((s) => s.mean !== null),
    html: skills.map((s) => `<div class="skill-row"><span>${esc(SKILL_LABEL[s.skill] ?? s.skill)}</span>${scoreBar(s.mean)}</div>`).join(""),
  };
}

function viewOverview(data: DashData): string {
  const active = activeRoster(data.roster);
  const pending = data.submissions.filter((s) => s.reviewedAt === null);
  const requests = data.roster.filter((m) => m.status === "Pending");
  const allSessions = [...data.sessionsByPlayer.values()].flat();
  const weekAgo = Date.now() - 7 * 86_400_000;
  const setsWeek = allSessions.filter((s) => s.startedAt >= weekAgo).length;
  const setsPrevWeek = allSessions.filter((s) => s.startedAt >= weekAgo - 7 * 86_400_000 && s.startedAt < weekAgo).length;
  const weekDelta = setsWeek - setsPrevWeek;
  const teamForms = active
    .map((m) => meanForm(data.sessionsByPlayer.get(m.playerUid) ?? []))
    .filter((f): f is number => f !== null);
  const teamForm = teamForms.length > 0
    ? Math.round(teamForms.reduce((a, b) => a + b, 0) / teamForms.length)
    : null;
  const skills = skillForm(allSessions);

  const focus =
    requests.length > 0
      ? { eyebrow: "Get started", title: `${requests.length} player${requests.length === 1 ? "" : "s"} waiting to join`, body: "Approve the players who are ready to join your team and share their sessions.", href: appPath("roster"), cta: "Review requests" }
      : pending.length > 0
        ? { eyebrow: "Review queue", title: `${pending.length} session${pending.length === 1 ? "" : "s"} waiting for you`, body: "Your players sent these for review. Open one to score it and write back.", href: appPath("queue"), cta: "Open review queue" }
        : { eyebrow: "All caught up", title: "Nothing waiting. Plan the next step.", body: "No submissions are queued. A good moment to set the next drill for your team.", href: appPath("assignments"), cta: "Set a drill" };

  const queuePreview = pending.slice(0, 4).map((s) => {
    const name = memberName(data.roster, s.playerUid);
    return `<a class="row-item" href="${appPath("player", s.playerUid, s.sessionId)}">${avatar(name)}
      <span style="flex:1"><strong>${esc(name)}</strong><br /><span class="muted">${esc(sessionTitle(data, s.sessionId))} · ${timeAgo(s.submittedAt)}</span></span>
      <span class="dot"></span><span class="row-arrow">${icon("chevronRight")}</span></a>`;
  }).join("");

  const coachFirstName = data.profileName?.trim().split(/\s+/)[0] || "Coach";
  const formNote = teamForm === null ? "No scores yet" : teamForm >= 75 ? "Looking strong" : teamForm >= 50 ? "Building up" : "Needs focus";
  const formTone = teamForm === null ? "flat" : teamForm >= 75 ? "up" : "warn";
  const weekNote = weekDelta === 0 ? "No change" : `${weekDelta > 0 ? "+" : ""}${weekDelta} vs last week`;

  return `<section class="overview-head">
      <div>
        <p class="eyebrow">Coach dashboard</p>
        <h2 class="overview-greeting">${greeting()}, ${esc(coachFirstName)}</h2>
        <p class="muted">Review new sessions, approve players, and plan the next drill.</p>
      </div>
    </section>

    <section class="hero-panel" aria-label="Team coaching focus">
      <img class="hero-panel-photo" src="/assets/volleyball-team-pavel-danilyuk.jpg" alt="" decoding="async" />
      <div class="hero-panel-copy">
        <span class="focus-pill"><i></i>${esc(focus.eyebrow)}</span>
        <h2>${esc(focus.title)}</h2>
        <p>${esc(focus.body)}</p>
        <div class="hero-panel-actions">
          <a class="btn btn-primary" href="${focus.href}">${esc(focus.cta)}</a>
          <a class="btn btn-hero-ghost" href="${appPath(requests.length ? "queue" : "roster")}">${requests.length ? "Review sessions" : "View roster"}</a>
        </div>
      </div>
      <div class="hero-panel-ring">
        <div class="form-ring" role="img" aria-label="${teamForm === null ? "Team form: no scored sessions yet" : `Team form: ${teamForm} out of 100`}">
          <svg class="hero-form-ring" viewBox="0 0 200 200" aria-hidden="true" focusable="false">
            <circle class="hero-form-track" cx="100" cy="100" r="84" />
            <circle class="hero-form-arc" cx="100" cy="100" r="84" pathLength="100" stroke-dasharray="${Math.max(0, Math.min(100, teamForm ?? 0))} 100" data-score="${Math.max(0, Math.min(100, teamForm ?? 0))}" />
          </svg>
          <div class="form-ring-inner"><strong>${teamForm ?? "—"}</strong><span>Team form</span></div>
        </div>
        <div class="hero-mini">
          <span><b>${active.length}</b> players</span>
          <span><b>${setsWeek}</b> sets this week</span>
        </div>
      </div>
    </section>

    <section class="stat-grid">
      ${statCard('<circle cx="9" cy="8" r="3"/><path d="M3 21v-3a6 6 0 0 1 12 0v3M16 5a3 3 0 0 1 0 6M18 14a5 5 0 0 1 3 4v3"/>', "indigo", active.length, "Active players", requests.length > 0 ? `${requests.length} to approve` : "All approved", requests.length > 0 ? "warn" : "flat")}
      ${statCard('<rect x="4" y="3" width="16" height="18" rx="2"/><path d="m8 9 1.5 1.5L12 8M14 9h3M8 16h9"/>', "rust", pending.length, "To review", pending.length > 0 ? "Needs you" : "All clear", pending.length > 0 ? "warn" : "flat")}
      ${statCard('<path d="M3 12h4l2-7 4 14 2-7h6"/>', "green", teamForm ?? "—", "Team form", formNote, formTone)}
      ${statCard('<path d="M4 19V5M4 15l6-6 4 4 6-8"/>', "violet", setsWeek, "Sets this week", weekNote, weekDelta >= 0 ? "up" : "warn")}
    </section>

    <div class="overview-grid">
      <section class="panel">
        <header class="panel-header">
          <h3 class="panel-title">${navIcon('<rect x="4" y="3" width="16" height="18" rx="2"/><path d="m8 9 1.5 1.5L12 8M14 9h3M8 16h9"/>')}Review queue</h3>
          <a class="panel-link" href="${appPath("queue")}">View all</a>
        </header>
        ${pending.length === 0
          ? emptyState("review", "Nothing waiting", "Sessions your players send for review will land here.")
          : `<div class="panel-list">${queuePreview}</div>`}
      </section>
      <section class="panel">
        <header class="panel-header">
          <h3 class="panel-title">${navIcon('<path d="M3 12h4l2-7 4 14 2-7h6"/>')}Form by skill</h3>
          <span class="panel-hint">All sessions</span>
        </header>
        ${skills.hasData
          ? `<div class="skill-list">${skills.html}</div>`
          : emptyState("chart", "No scores yet", "Submitted sessions will show team form per skill here.")}
      </section>
    </div>

    <div class="overview-grid">
      <section class="panel">
        <header class="panel-header">
          <h3 class="panel-title">${navIcon('<path d="M4 19V5M4 15l6-6 4 4 6-8"/>')}Training activity</h3>
          <span class="panel-hint">Last 7 days</span>
        </header>
        <div class="activity-chart">${activityBars(allSessions)}</div>
      </section>
      <section class="panel">
        <header class="panel-header">
          <h3 class="panel-title">${navIcon('<circle cx="9" cy="8" r="3"/><path d="M3 21v-3a6 6 0 0 1 12 0v3M16 5a3 3 0 0 1 0 6M18 14a5 5 0 0 1 3 4v3"/>')}Roster</h3>
          <a class="panel-link" href="${appPath("roster")}">Manage</a>
        </header>
        ${active.length === 0
          ? emptyState("team", "No players yet", "Approve a request to start building your team.")
          : `<div class="panel-list">${active.slice(0, 4).map((m) => playerRow(data, m)).join("")}</div>`}
      </section>
    </div>`;
}

function sessionTitle(data: DashData, sessionId: string): string {
  for (const sessions of data.sessionsByPlayer.values()) {
    const s = sessions.find((x) => x.id === sessionId);
    if (s) return `${SKILL_LABEL[s.skill] ?? s.skill} · ${s.repCount} reps`;
  }
  return "Session";
}

function playerRow(data: DashData, m: RosterMember): string {
  const sessions = data.sessionsByPlayer.get(m.playerUid) ?? [];
  const form = meanForm(sessions);
  return `<a class="row-item" data-search-item="${esc(`${m.playerName} ${m.position ?? ""}`)}" href="${appPath("player", m.playerUid)}">${avatar(m.playerName || "Player")}
    <span style="flex:1"><strong>${esc(m.playerName || "Unnamed player")}</strong><br />
    <span class="muted">${sessions.length} set${sessions.length === 1 ? "" : "s"}${form !== null ? ` · form ${form}` : ""}</span></span>
    ${messageBadge(playerUnreadCount(data.questions, m.playerUid))}<span class="row-arrow">${icon("chevronRight")}</span></a>`;
}

// ---- Queue ------------------------------------------------------------------

function viewQueue(data: DashData, highlight: string | null): string {
  void highlight;
  const pending = data.submissions.filter((s) => s.reviewedAt === null);
  if (pending.length === 0) {
    return `<div class="card" style="margin-top:14px">${emptyState("review", "Nothing waiting", "Sessions your players submit for review land here.")}</div>`;
  }
  return `<div class="card" data-search-region style="margin-top:14px">
    <div class="chip-row" style="margin-bottom:8px">${iconChip("review", "indigo")}<h3 class="section-title">Review queue</h3></div>
    <p class="muted">${pending.length} session${pending.length === 1 ? "" : "s"} ready for your feedback. Select one to open its measurements.</p>
    <label class="coach-search">${icon("search")}<input type="search" data-coach-search aria-label="Search review queue" placeholder="Find a player or skill" /></label>
    <p class="search-empty" role="status" data-search-empty hidden>No matching sessions. Try another name or skill.</p>
    <div class="row-list">${pending.map((s) => {
      const name = memberName(data.roster, s.playerUid);
      return `<a class="row-item" data-search-item="${esc(`${name} ${sessionTitle(data, s.sessionId)}`)}" href="${appPath("player", s.playerUid, s.sessionId)}">${avatar(name)}
        <span style="flex:1"><strong>${esc(name)}</strong><br />
        <span class="muted">${esc(sessionTitle(data, s.sessionId))} · sent ${timeAgo(s.submittedAt)}</span></span>
        <span class="dot"></span><span class="row-arrow">${icon("chevronRight")}</span></a>`;
    }).join("")}</div></div>`;
}

// ---- Roster -----------------------------------------------------------------

function viewRoster(data: DashData): string {
  const requests = data.roster.filter((m) => m.status === "Pending");
  const active = activeRoster(data.roster);
  const past = data.roster.filter((m) => m.status === "Ended");
  return `<div class="coach-page-intro"><p class="eyebrow">Your team</p><h2>Your players</h2><p class="muted">Find a player, review their sessions, and send feedback.</p></div><div class="page-grid">
    ${requests.length ? `<div class="card span-2"><div class="chip-row" style="margin-bottom:8px">${iconChip("userPlus", "rust")}<h3 class="section-title">Join requests (${requests.length})</h3></div>
      <div class="row-list">${requests.map((m) => `
          <div class="row-item">${avatar(m.playerName || "Player")}
            <span style="flex:1"><strong>${esc(m.playerName || "Unnamed player")}</strong><br />
            <span class="muted">${esc(m.position ?? "Position not set")} · asked ${timeAgo(m.requestedAt)}</span></span>
            <button class="btn btn-primary btn-sm" data-action="approve" data-player="${esc(m.playerUid)}">Approve</button>
            <button class="btn btn-ghost btn-sm" data-action="decline" data-player="${esc(m.playerUid)}">Decline</button>
          </div>`).join("")}</div>
    </div>` : ""}
    <div class="card span-2" data-search-region><div class="chip-row" style="margin-bottom:8px">${iconChip("team", "rust")}<h3 class="section-title">On the team</h3><span class="muted" role="status" data-search-count>${active.length} players</span></div>
      ${active.length ? `<label class="coach-search">${icon("search")}<input type="search" data-coach-search aria-label="Search team" placeholder="Search by name or position" /></label><p class="search-empty" data-search-empty hidden>No matching players. Try another name or position.</p>` : ""}
      ${active.length === 0 ? emptyState("team", "Nobody yet", "Approved players appear here.") : `<div class="row-list">${active.map((m) => playerRow(data, m)).join("")}</div>`}
    </div>
    ${past.length ? `<details class="card span-2 history-disclosure"><summary>Past players <span class="muted">${past.length}</span>${icon("chevronDown")}</summary>
      <div class="row-list">${past.map((m) => `
        <div class="row-item">${avatar(m.playerName || "Player")}
          <span style="flex:1"><strong>${esc(m.playerName || "Unnamed player")}</strong></span>
          <span class="muted">Connection ended</span>
        </div>`).join("")}</div>
    </details>` : ""}
  </div>`;
}

// ---- Player detail ----------------------------------------------------------

async function viewPlayer(coachUid: string, data: DashData, playerUid: string): Promise<string> {
  const member = data.roster.find((m) => m.playerUid === playerUid);
  if (!member) {
    return `<div class="card" style="margin-top:14px">${emptyState("team", "Not on your roster", "This player is not on your team, so there is nothing to show.")}</div>`;
  }
  const sessions = data.sessionsByPlayer.get(playerUid) ?? [];
  const form = meanForm(sessions);
  const weak = weakestCue(sessions);
  const skills = perSkillMeans(sessions);
  const [notes, assignments] = await Promise.all([
    getNotesForPlayer(coachUid, playerUid),
    getAssignmentsForPlayer(coachUid, playerUid),
  ]);

  const sessionCards = sessions.length === 0
    ? emptyState("session", "No shared sessions", "This player has not submitted a session yet.")
    : sessions.map((s) => sessionCard(coachUid, playerUid, s, notes)).join("");

  const conversation = await renderCoachQuestions(coachUid,
    data.questions === null ? null : data.questions.filter(q => q.playerUid === playerUid), [member]);
  return `<div class="player-profile-toolbar"><a href="${appPath("roster")}" style="font-size:13.5px;font-weight:700;color:var(--ink-soft);text-decoration:none">${icon("chevronLeft")} Back to roster</a>
    <details class="player-messages" data-message-thread="${esc(playerUid)}"${expandedConversations.has(playerUid) ? " open" : ""}>
      <summary class="btn btn-ghost">${icon("message")} Messages ${messageBadge(playerUnreadCount(data.questions, playerUid))}</summary>
      <div class="player-message-content"><h2>Messages with ${esc(member.playerName || "Player")}</h2>${conversation}</div>
    </details></div>
  <div class="card" style="margin-top:10px"><div class="chip-row">${avatar(member.playerName || "Player")}
    <div style="flex:1"><h2 style="font-size:22px">${esc(member.playerName || "Unnamed player")}</h2>
    <p class="muted" style="margin:2px 0 0">${esc(member.position ?? "Position not set")} · <span class="pill${member.status === "Active" ? "-green" : ""}" style="margin-left:4px">${member.status}</span></p></div>
    ${member.status === "Pending" ? `<button class="btn btn-primary btn-sm" data-action="approve" data-player="${esc(playerUid)}">Approve</button>` : ""}
  </div></div>

  <div class="stat-strip" style="margin-top:14px">
    <div class="stat"><b>${sessions.length}</b><span>Sets shared</span></div>
    <div class="stat"><b>${form ?? "—"}</b><span>Mean form</span></div>
    <div class="stat"><b>${assignments.filter((a) => a.status !== "Completed").length}</b><span>Open drills</span></div>
    <div class="stat"><b>${notes.length}</b><span>Notes sent</span></div>
  </div>

  ${weak ? `<div class="card-dark" style="margin-top:14px">
    <p class="eyebrow">Lowest-scoring cue</p>
    <h3 style="font-size:20px;margin-bottom:6px">${esc(cueLabel(weak.cueId))} — ${Math.round(weak.mean * 100)}</h3>
  </div>` : ""}

  <div class="page-grid">
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("chart", "green")}<h3>By skill</h3></div>
      ${skills.map((s) => `<div style="display:flex;align-items:center;gap:12px;padding:7px 0">
        <span style="flex:1;font-weight:600">${SKILL_LABEL[s.skill]}</span>${scoreBar(s.mean)}</div>`).join("")}
    </div>
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("clipboard", "violet")}<h3>Drills set</h3></div>
      ${assignments.length === 0 ? `<p class="muted">No drills set yet.</p>` : assignments.slice(0, 4).map((a) => `
        <div style="padding:8px 0;border-top:1px solid var(--line)"><strong>${esc(a.title)}</strong><br />
        <span class="muted">${a.targetSets} × ${a.targetReps} reps · ${esc(a.status)}${a.dueAt && a.status !== "Completed" ? ` · ${dueLabel(a.dueAt)}` : ""}</span></div>`).join("")}
      <div style="margin-top:10px"><a class="btn btn-ghost btn-sm" href="${appPath("assignments")}">Set a drill</a></div>
    </div>
  </div>

  <div class="card" style="margin-top:14px"><div class="chip-row" style="margin-bottom:8px">${iconChip("session", "green")}<h3 class="section-title">Shared sessions</h3></div>
    ${sessionCards}</div>

  <div class="card" style="margin-top:14px"><div class="chip-row" style="margin-bottom:8px">${iconChip("note", "violet")}<h3>Your notes</h3></div>
    ${notes.length === 0 ? `<p class="muted">No notes yet. Open a session above to write the first one.</p>` : notes.slice(0, 5).map((n) => `
      <div class="note-bubble">${esc(n.text)}<small>${fullDate(n.createdAt)}${n.sessionId ? " · on a session" : " · general"}</small></div>`).join("")}
  </div>`;
}

function sessionCard(coachUid: string, playerUid: string, s: TrainingSession, notes: CoachNote[]): string {
  void coachUid;
  void playerUid;
  const open = expandedSessions.has(s.id);
  const answered = notes.some((n) => n.sessionId === s.id);
  const cues = cuesFor(s.skill);
  const cueRows = cues.map((cue) => {
    const measured = s.reps.flatMap((r) => r.cueScores).filter((c) => c.cueId === cue.id && c.score !== null);
    if (measured.length === 0) {
      return `<tr><td>${esc(cue.label)}</td><td class="muted" data-cue-label="Athlete">Not measured</td><td data-cue-label="Score">—</td><td class="muted" data-cue-label="Reference">${esc(formatTarget(cue.target, cue.unit))}</td></tr>`;
    }
    const meanScore = Math.round((measured.reduce((a, c) => a + (c.score ?? 0), 0) / measured.length) * 100);
    const meanMeas = measured.reduce((a, c) => a + (c.measured ?? 0), 0) / measured.length;
    const hasMeas = measured.some((c) => c.measured !== null);
    return `<tr><td><strong>${esc(cue.label)}</strong><br /><span class="muted" style="font-size:12px">${measured.length} reps</span></td>
      <td data-cue-label="Athlete">${hasMeas ? esc(formatMeasured(meanMeas, cue.unit)) : "—"}</td>
      <td data-cue-label="Score">${scoreBar(meanScore)}</td>
      <td class="muted" data-cue-label="Reference">${esc(formatTarget(cue.target, cue.unit))}</td></tr>`;
  }).join("");

  return `<details class="coach-session" data-session-details="${esc(s.id)}"${open ? " open" : ""}>
    <summary>
      <span class="session-summary-copy"><strong>${esc(SKILL_LABEL[s.skill] ?? s.skill)}</strong>
        <span class="muted">${s.repCount} reps · ${fullDate(s.startedAt)}</span></span>
      ${s.formScore !== null ? `<span class="score-num">${s.formScore}</span>` : ""}
      <span class="session-state${answered ? " answered" : ""}">${answered ? "Feedback sent" : "To review"}</span>
      <span class="session-chevron">${icon("chevronDown")}</span>
    </summary>
    <div class="session-detail"><template data-session-template><div class="session-detail-inner">
      ${s.weakestCue ? `<p style="margin:0 0 10px"><span class="pill">${esc(cueLabel(s.weakestCue))} — focus next</span></p>` : ""}
      <div class="cue-table-scroll" role="region" aria-label="Session cue measurements" tabindex="0"><table class="cues"><thead><tr><th>Cue</th><th>Athlete</th><th>Score</th><th>Target</th></tr></thead><tbody>${cueRows}</tbody></table></div>
      ${sessionExplanationPanel(s.id)}
      <div class="field" style="margin-top:12px"><label for="note-${esc(s.id)}">Your note to this player</label>
        <textarea id="note-${esc(s.id)}" data-note-draft="${esc(s.id)}" maxlength="800" rows="4" placeholder="One thing they did well, then a clear focus for the next set."></textarea></div>
      <div class="note-composer-footer"><span class="muted" data-note-count="${esc(s.id)}">0/800</span><button class="btn btn-primary" disabled data-action="send-note" data-session="${esc(s.id)}" data-player="${esc(playerUid)}">${icon("message")} Send feedback</button></div>
    </div></template></div>
  </details>`;
}

// ---- Assignments ------------------------------------------------------------

function viewAssignments(data: DashData, editing: string | null): string {
  void editing;
  const active = activeRoster(data.roster);
  const playerOptions = active.map((m) => `<option value="${esc(m.playerUid)}">${esc(m.playerName || "Player")}</option>`).join("");
  return `<div class="page-grid">
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("clipboard", "violet")}<h3 class="section-title">Set a drill</h3></div>
      ${active.length === 0 ? emptyState("clipboard", "Approve a player first", "A drill needs someone to do it.") : `
      <div id="assign-error"></div>
      <form id="assign-form">
        <div class="field"><label for="a-player">Player</label><select id="a-player">${playerOptions}</select></div>
        <div class="field"><label for="a-title">Drill name</label><input id="a-title" maxlength="60" required placeholder="Cross-court cut shot" /></div>
        <div class="form-grid">
          <div class="field"><label for="a-skill">Target skill</label><select id="a-skill">
            ${["Serving", "Receiving", "Setting", "Spiking", "Blocking"].map((s) => `<option>${s}</option>`).join("")}</select></div>
          <div class="field"><label for="a-level">Difficulty</label><select id="a-level"><option>Beginner</option><option>Intermediate</option><option>Advanced</option></select></div>
        </div>
        <div class="form-grid">
          <div class="field"><label for="a-sets">Sets</label><input id="a-sets" type="number" min="1" max="10" value="3" /></div>
          <div class="field"><label for="a-reps">Reps per set</label><input id="a-reps" type="number" min="1" max="50" value="10" /></div>
        </div>
        <div class="field"><label for="a-due">Due in (days, blank = none)</label><input id="a-due" type="number" min="1" max="90" placeholder="No deadline" /></div>
        <div class="field"><label for="a-summary">Summary</label><input id="a-summary" maxlength="200" placeholder="One line on what this drill is for." /></div>
        <div class="field"><label for="a-instructions">Instructions</label><textarea id="a-instructions" maxlength="1200" placeholder="Step by step. What to set up, what to do, what to watch for."></textarea></div>
        <button class="btn btn-primary" style="width:100%" type="submit">Assign it</button>
      </form>`}
    </div>
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("review", "indigo")}<h3 class="section-title">Assigned (${data.assignments.length})</h3></div>
      ${data.assignments.length === 0 ? emptyState("review", "No drills set", "Write a drill and hand it to one of your players. They see it in their Coach tab with your instructions attached.") : `
      <div class="row-list">${data.assignments.map((a) => `
        <div class="row-item"><div style="flex:1"><strong>${esc(a.title)}</strong><br />
          <span class="muted">${esc(memberName(data.roster, a.playerUid))} · ${esc(SKILL_LABEL[a.skill] ?? a.skill)} · ${a.targetSets} × ${a.targetReps} reps · ${esc(a.status)}</span></div>
          <button class="btn btn-ghost btn-sm" data-action="delete-assignment" data-id="${esc(a.id)}">Delete</button>
        </div>`).join("")}</div>`}
    </div>
  </div>`;
}

// ---- Actions ----------------------------------------------------------------

function val(root: ParentNode, id: string): string {
  return (root.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(`#${id}`)?.value ?? "").trim();
}

function wireSignOut(root: HTMLElement): void {
  root.querySelectorAll<HTMLButtonElement>("[data-action='sign-out']").forEach((b) =>
    b.onclick = async () => {
      stopLive();
      noteDrafts.clear();
      await auth.signOut();
      go("/");
    },
  );
}

function wireActions(root: HTMLElement, coachUid: string, refresh: () => void): void {
  wireSignOut(root);
  const showStatus = (message: string, error = false) => {
    const status = root.querySelector<HTMLElement>("#action-status");
    if (!status) return;
    clearTimeout(statusTimer);
    status.innerHTML = `<div class="coach-toast${error ? " is-error" : ""}"${error ? ' role="alert"' : ""}>${icon(error ? "message" : "check")}<span>${esc(message)}</span><button type="button" aria-label="Dismiss message">×</button></div>`;
    status.querySelector("button")?.addEventListener("click", () => { status.replaceChildren(); });
    if (!error) statusTimer = setTimeout(() => status.replaceChildren(), 6000);
  };

  const act = async (action: string, el: HTMLElement) => {
    const player = el.dataset["player"] ?? "";
    const original = el.innerHTML;
    if (el instanceof HTMLButtonElement) {
      if (el.disabled) return;
      el.disabled = true;
    }
    try {
      if (action === "approve" || action === "decline" || action === "remove") {
        await setMemberStatus(coachUid, player, action === "approve" ? "Active" : "Ended");
      } else if (action === "send-note") {
        const sessionId = el.dataset["session"] ?? "";
        const text = val(root, `note-${CSS.escape(sessionId)}`);
        if (!text) throw new Error("Write a note before sending.");
        el.textContent = "Sending…";
        (el as HTMLButtonElement).disabled = true;
        await postNote({ id: newId(), coachUid, playerUid: player, sessionId, text: text.slice(0, 800), createdAt: Date.now() });
        noteDrafts.delete(sessionId);
        showStatus("Feedback sent to the player.");
      } else if (action === "delete-assignment") {
        await deleteAssignment(el.dataset["id"] ?? "");
      }
    } catch (e) {
      console.error(e);
      showStatus("Could not save this change. Check your connection and try again. Your text is still here.", true);
      el.innerHTML = original;
      if (el instanceof HTMLButtonElement) el.disabled = false;
      return;
    }
    refresh();
  };

  const actionArea = root.querySelector<HTMLElement>("#dash-body");
  if (actionArea) actionArea.onclick = (event) => {
    const element = (event.target as Element).closest<HTMLElement>("[data-action]");
    if (element && actionArea.contains(element) && element.dataset.action !== "sign-out") void act(element.dataset.action ?? "", element);
  };

  const assignForm = root.querySelector<HTMLFormElement>("#assign-form");
  assignForm?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const button = assignForm.querySelector("button");
    if (button) button.disabled = true;
    const dueDays = Number.parseInt(val(root, "a-due"), 10);
    const sets = Math.min(10, Math.max(1, Number.parseInt(val(root, "a-sets"), 10) || 3));
    const reps = Math.min(50, Math.max(1, Number.parseInt(val(root, "a-reps"), 10) || 10));
    const title = val(root, "a-title");
    const errBox = root.querySelector("#assign-error");
    if (!title) {
      if (errBox) errBox.innerHTML = `<div class="form-error">Give the drill a name.</div>`;
      if (button) button.disabled = false;
      return;
    }
    try {
      const skill = val(root, "a-skill") as Skill;
      await saveAssignment({
        id: newId(),
        coachUid,
        playerUid: val(root, "a-player"),
        title: title.slice(0, 60),
        skill,
        level: val(root, "a-level") || "Beginner",
        summary: val(root, "a-summary").slice(0, 200),
        instructions: val(root, "a-instructions").slice(0, 1200),
        targetSets: sets,
        targetReps: reps,
        dueAt: Number.isFinite(dueDays) && dueDays > 0 ? Date.now() + dueDays * 86_400_000 : null,
        status: "Pending",
        createdAt: Date.now(),
        libraryDrillId: null,
      });
    } catch (e) {
      console.error(e);
      if (errBox) errBox.innerHTML = `<div class="form-error" role="alert">Could not assign this drill. Your entries are saved on this screen; please try again.</div>`;
      if (button) button.disabled = false;
      return;
    }
    refresh();
  });

}
