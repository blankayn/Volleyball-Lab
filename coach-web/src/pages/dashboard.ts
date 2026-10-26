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
  saveProfile,
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
import { avatar, emptyState, errorCard, iconChip, scoreBar, spinner } from "../lib/ui";

type Tab = "overview" | "queue" | "roster" | "assignments" | "account" | "player";

interface DashData {
  roster: RosterMember[];
  submissions: Submission[];
  assignments: Assignment[];
  sessionsByPlayer: Map<string, TrainingSession[]>;
  profileName: string;
}

let unsubscribers: (() => void)[] = [];
let expandedSession: string | null = null;
let disposeSidebar: (() => void) | null = null;

let dashboardVersion = 0;
export function disposeDashboard(): void {
  dashboardVersion++;
  stopLive();
  disposeSidebar?.();
  disposeSidebar = null;
  expandedSession = null;
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
  const [roster, submissions, assignments, profile] = await Promise.all([
    getRoster(coachUid),
    getSubmissions(coachUid),
    getAssignments(coachUid),
    getProfile(coachUid),
  ]);
  const sessionsByPlayer = new Map<string, TrainingSession[]>();
  await Promise.all(
    activeRoster(roster).map(async (m) => {
      sessionsByPlayer.set(m.playerUid, await getSessionsFor(m.playerUid, coachUid));
    }),
  );
  return { roster, submissions, assignments, sessionsByPlayer, profileName: profile.name };
}

const PAGE_LABELS: Record<Tab, string> = {
  overview: "Overview",
  queue: "Review queue",
  roster: "Roster",
  assignments: "Assignments",
  account: "Account",
  player: "Player profile",
};

function navIcon(paths: string): string {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" class="ui-icon" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${paths}</svg>`;
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
    if (!open && sidebar.contains(document.activeElement)) topToggle.focus();
  };

  if (isMobile()) topToggle.setAttribute("aria-expanded", "false");
  else setCollapsed(savedCollapsed(), false);

  topToggle.addEventListener("click", () => {
    if (isMobile()) setDrawer(!shell.classList.contains("is-mobile-open"));
    else setCollapsed(!shell.classList.contains("is-collapsed"));
  });
  collapseToggle?.addEventListener("click", () => setCollapsed(!shell.classList.contains("is-collapsed")));
  scrim?.addEventListener("click", () => setDrawer(false));
  sidebar.addEventListener("click", (event) => {
    if ((event.target as Element).closest("a")) setDrawer(false);
  });

  const onKeydown = (event: KeyboardEvent) => {
    if (event.key === "Escape" && isMobile() && shell.classList.contains("is-mobile-open")) setDrawer(false);
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
): Promise<void> {
  stopLive();
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
          <div class="app-title-line"><h1 id="page-heading">${PAGE_LABELS[activeTab]}</h1><span class="dash-team-name" id="team-name">Your team</span></div>
        </div>
        <div class="app-topbar-actions">
          <span class="sync-pill"><i></i>Live</span>
          <a class="btn btn-primary btn-sm topbar-cta" href="${appPath("assignments")}" aria-label="Set a new drill">${navIcon('<path d="M12 5v14M5 12h14"/>')}<span>New drill</span></a>
        </div>
      </header>
      <main class="app-content" id="dashboard-content">
        <div id="action-status" role="status" aria-live="polite"></div>
        <div id="dash-body">${spinner("Loading your team")}</div>
      </main>
    </div>
  </div>`;
  document.title = `${PAGE_LABELS[activeTab]} — Volleyball Lab Coach Portal`;
  wireSidebar(root);
  wireSignOut(root);

  const body = root.querySelector<HTMLElement>("#dash-body");
  if (!body) return;

  let request = 0;
  let dirty = false;
  body.addEventListener("input", () => { dirty = true; });
  body.addEventListener("change", () => { dirty = true; });
  const render = async (live = false) => {
    if (live && dirty) return;
    const attempt = ++request;
    try {
      const data = await loadData(coachUid);
      if (version !== dashboardVersion || attempt !== request || !body.isConnected || (live && dirty)) return;
      nameCache = new Map(data.roster.map((m) => [m.playerUid, m.playerName || "Player"]));
      // Refresh badges and the selected-player link without replacing the shell.
      const nav = root.querySelector(".sidebar-nav");
      if (nav) {
        nav.innerHTML = sidebarLinks(
          activeTab,
          playerUid,
          data.submissions.filter((s) => s.reviewedAt === null).length,
          data.roster.filter((m) => m.status === "Pending").length,
        );
      }
      const html = await viewFor(activeTab, coachUid, data, playerUid);
      if (version !== dashboardVersion || attempt !== request || !body.isConnected || (live && dirty)) return;
      body.innerHTML = html;
      dirty = false;
      const coachName = data.profileName || "Coach";
      const teamName = root.querySelector(".dash-team-name");
      if (teamName) teamName.textContent = data.profileName ? `${data.profileName}’s team` : "Your team";
      const sidebarUserName = root.querySelector("#sidebar-user-name");
      if (sidebarUserName) sidebarUserName.textContent = coachName;
      const workspaceName = root.querySelector("#workspace-name");
      if (workspaceName) workspaceName.textContent = data.profileName ? `${data.profileName}’s workspace` : "Coach workspace";
      const avatarText = initials(coachName);
      const userAvatar = root.querySelector("#sidebar-user-avatar");
      if (userAvatar) userAvatar.textContent = avatarText;
      const workspaceAvatar = root.querySelector("#workspace-avatar");
      if (workspaceAvatar) workspaceAvatar.textContent = avatarText;
      if (activeTab === "player" && playerUid) {
        const heading = root.querySelector("#page-heading");
        if (heading) heading.textContent = nameCache.get(playerUid) || PAGE_LABELS.player;
      }
      wireActions(root, coachUid, () => void render());
    } catch (e) {
      console.error(e);
      if (version !== dashboardVersion || attempt !== request || !body.isConnected) return;
      body.innerHTML = errorCard(
        "Could not load your team. Check your connection and that Firestore rules are deployed.",
        "reload",
      );
      body.querySelector("[data-retry]")?.addEventListener("click", () => void render());
    }
  };

  // Live queue + roster counts; the body re-renders on change.
  unsubscribers = [
    subscribe(queueQuery(coachUid), () => void render(true), () => undefined),
    subscribe(rosterQuery(coachUid), () => void render(true), () => undefined),
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
      return viewAccount(coachUid);
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
      ? { eyebrow: "Get started", title: `${requests.length} player${requests.length === 1 ? "" : "s"} waiting to join`, body: "Review the requests on your roster. Nothing else needs you first.", href: appPath("roster"), cta: "Review requests" }
      : pending.length > 0
        ? { eyebrow: "Review queue", title: `${pending.length} session${pending.length === 1 ? "" : "s"} waiting for you`, body: "Your players sent these for review. Open one to score it and write back.", href: appPath("queue"), cta: "Open review queue" }
        : { eyebrow: "All caught up", title: "Nothing waiting. Plan the next step.", body: "No submissions are queued. A good moment to set the next drill for your team.", href: appPath("assignments"), cta: "Set a drill" };

  const queuePreview = pending.slice(0, 4).map((s) => {
    const name = memberName(data.roster, s.playerUid);
    return `<a class="row-item" href="${appPath("player", s.playerUid)}">${avatar(name)}
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
        <p class="muted">Here’s your team at a glance — review work, form and the next drill.</p>
      </div>
    </section>

    <section class="hero-panel">
      <div class="hero-panel-copy">
        <span class="focus-pill"><i></i>${esc(focus.eyebrow)}</span>
        <h2>${esc(focus.title)}</h2>
        <p>${esc(focus.body)}</p>
        <div class="hero-panel-actions">
          <a class="btn btn-primary" href="${focus.href}">${esc(focus.cta)}</a>
          <a class="btn btn-hero-ghost" href="${appPath("roster")}">View roster</a>
        </div>
      </div>
      <div class="hero-panel-ring">
        <div class="form-ring" style="--pct:${teamForm ?? 0}">
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
    </div>

    <p class="overview-footnote">Connected to the player app through the shared backend — requests, sessions and notes sync both ways. Pose analysis stays on athletes’ phones; only scores travel.</p>`;
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
  return `<a class="row-item" href="${appPath("player", m.playerUid)}">${avatar(m.playerName || "Player")}
    <span style="flex:1"><strong>${esc(m.playerName || "Unnamed player")}</strong><br />
    <span class="muted">${sessions.length} set${sessions.length === 1 ? "" : "s"}${form !== null ? ` · form ${form}` : ""}</span></span>
    <span class="row-arrow">${icon("chevronRight")}</span></a>`;
}

// ---- Queue ------------------------------------------------------------------

function viewQueue(data: DashData, highlight: string | null): string {
  void highlight;
  const pending = data.submissions.filter((s) => s.reviewedAt === null);
  if (pending.length === 0) {
    return `<div class="card" style="margin-top:14px">${emptyState("review", "Nothing waiting", "Sessions your players submit for review land here. They choose what to send; nothing is shared automatically.")}</div>`;
  }
  return `<div class="card" style="margin-top:14px">
    <div class="chip-row" style="margin-bottom:8px">${iconChip("review", "indigo")}<h3 class="section-title">Review queue</h3></div>
    <div class="row-list">${pending.map((s) => {
      const name = memberName(data.roster, s.playerUid);
      return `<a class="row-item" href="${appPath("player", s.playerUid)}">${avatar(name)}
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
  return `<div class="page-grid">
    <div class="card span-2"><div class="chip-row" style="margin-bottom:8px">${iconChip("userPlus", "rust")}<h3 class="section-title">Wants to join ${requests.length > 0 ? `(${requests.length})` : ""}</h3></div>
      ${requests.length === 0
        ? emptyState("userPlus", "No requests", "Players pick you from their app's Coach tab. Their requests land here for you to approve.")
        : `<div class="row-list">${requests.map((m) => `
          <div class="row-item">${avatar(m.playerName || "Player")}
            <span style="flex:1"><strong>${esc(m.playerName || "Unnamed player")}</strong><br />
            <span class="muted">${esc(m.position ?? "Position not set")} · asked ${timeAgo(m.requestedAt)}</span></span>
            <button class="btn btn-primary btn-sm" data-action="approve" data-player="${esc(m.playerUid)}">Approve</button>
            <button class="btn btn-ghost btn-sm" data-action="decline" data-player="${esc(m.playerUid)}">Decline</button>
          </div>`).join("")}</div>`}
    </div>
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("team", "rust")}<h3 class="section-title">On the team (${active.length})</h3></div>
      ${active.length === 0 ? emptyState("team", "Nobody yet", "Approved players appear here.") : `<div class="row-list">${active.map((m) => playerRow(data, m)).join("")}</div>`}
    </div>
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("archive", "indigo")}<h3 class="section-title">Past players</h3></div>
      ${past.length === 0 ? `<p class="muted">Nobody here. Removed players stay listed so history is not rewritten.</p>` : `<div class="row-list">${past.map((m) => `
        <div class="row-item">${avatar(m.playerName || "Player")}
          <span style="flex:1"><strong>${esc(m.playerName || "Unnamed player")}</strong></span>
          <button class="btn btn-ghost btn-sm" data-action="remove" data-player="${esc(m.playerUid)}">Remove</button>
        </div>`).join("")}</div>`}
    </div>
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
    ? emptyState("session", "No shared sessions", "This player has not submitted a session yet. Only sessions they choose to send appear here.")
    : sessions.map((s) => sessionCard(coachUid, playerUid, s, notes)).join("");

  return `<div style="margin-top:14px"><a href="${appPath("roster")}" style="font-size:13.5px;font-weight:700;color:var(--ink-soft);text-decoration:none">${icon("chevronLeft")} Back to roster</a></div>
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
    <p style="margin:0">Pooled across every rep recorded of it (min 3 measured reps). The same arithmetic the athlete's own app shows them.</p>
  </div>` : ""}

  <div class="page-grid">
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("chart", "green")}<h3>By skill</h3></div>
      ${skills.map((s) => `<div style="display:flex;align-items:center;gap:12px;padding:7px 0">
        <span style="flex:1;font-weight:600">${SKILL_LABEL[s.skill]}</span>${scoreBar(s.mean)}</div>`).join("")}
    </div>
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("clipboard", "violet")}<h3>Drills set</h3></div>
      ${assignments.length === 0 ? `<p class="muted">No drills set yet.</p>` : assignments.slice(0, 4).map((a) => `
        <div style="padding:8px 0;border-top:1px solid var(--line)"><strong>${esc(a.title)}</strong><br />
        <span class="muted">${a.targetSets} × ${a.targetReps} reps · ${esc(a.status)}${a.dueAt ? ` · ${dueLabel(a.dueAt)}` : ""}</span></div>`).join("")}
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
  const open = expandedSession === s.id;
  const answered = notes.some((n) => n.sessionId === s.id);
  const cues = cuesFor(s.skill);
  const cueRows = cues.map((cue) => {
    const measured = s.reps.flatMap((r) => r.cueScores).filter((c) => c.cueId === cue.id && c.score !== null);
    if (measured.length === 0) {
      return `<tr><td>${esc(cue.label)}</td><td class="muted">Not measurable from this camera angle</td><td>—</td><td class="muted">${esc(formatTarget(cue.target, cue.unit))}</td></tr>`;
    }
    const meanScore = Math.round((measured.reduce((a, c) => a + (c.score ?? 0), 0) / measured.length) * 100);
    const meanMeas = measured.reduce((a, c) => a + (c.measured ?? 0), 0) / measured.length;
    const hasMeas = measured.some((c) => c.measured !== null);
    return `<tr><td><strong>${esc(cue.label)}</strong><br /><span class="muted" style="font-size:12px">${measured.length} reps</span></td>
      <td>${hasMeas ? esc(formatMeasured(meanMeas, cue.unit)) : "—"}</td>
      <td>${scoreBar(meanScore)}</td>
      <td class="muted">${esc(formatTarget(cue.target, cue.unit))}</td></tr>`;
  }).join("");

  return `<div style="border-top:1px solid var(--line);padding:14px 0">
    <div class="chip-row" style="cursor:pointer" role="button" tabindex="0" aria-expanded="${open}" data-action="toggle-session" data-session="${esc(s.id)}">
      <div style="flex:1"><strong>${esc(SKILL_LABEL[s.skill] ?? s.skill)}</strong>
        <span class="muted"> · ${s.repCount} reps · ${fullDate(s.startedAt)}</span></div>
      ${s.formScore !== null ? `<span class="score-num">${s.formScore}</span>` : ""}
      ${answered ? `<span class="pill-green pill">Answered</span>` : `<span class="dot"></span>`}
      <span style="color:var(--ink-faint)">${icon(open ? "chevronDown" : "chevronRight")}</span>
    </div>
    ${open ? `<div style="margin-top:12px">
      ${s.weakestCue ? `<p style="margin:0 0 10px"><span class="pill">${esc(cueLabel(s.weakestCue))} — focus next</span></p>` : ""}
      <table class="cues"><thead><tr><th>Cue</th><th>Athlete</th><th>Score</th><th>Target</th></tr></thead><tbody>${cueRows}</tbody></table>
      <p class="muted" style="font-size:12px">Computed on-device from the athlete's stored pose data. Thresholds are first-pass coaching values, not measured ones.</p>
      <div class="field" style="margin-top:12px"><label for="note-${esc(s.id)}">Your note to this player</label>
        <textarea id="note-${esc(s.id)}" maxlength="800" placeholder="What should they change before the next set?"></textarea></div>
      <button class="btn btn-primary btn-sm" data-action="send-note" data-session="${esc(s.id)}" data-player="${esc(playerUid)}">Send note</button>
    </div>` : ""}
  </div>`;
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

// ---- Account ----------------------------------------------------------------

async function viewAccount(coachUid: string): Promise<string> {
  const profile = await getProfile(coachUid);
  const specOptions = ["AllAround", "Serving", "Receiving", "Setting", "Spiking", "Blocking"]
    .map((s) => `<option value="${s}"${(profile.specialisation ?? "AllAround") === s ? " selected" : ""}>${s === "AllAround" ? "All-around" : s}</option>`)
    .join("");
  return `<div class="page-grid"><div class="card">
    <div class="chip-row" style="margin-bottom:12px">${iconChip("user", "rust")}<h3 class="section-title">Team &amp; credentials</h3></div>
    <div id="profile-saved"></div>
    <form id="profile-form">
      <div class="form-grid">
        <div class="field"><label for="p-name">Your name</label><input id="p-name" required maxlength="60" value="${esc(profile.name)}" /></div>
        <div class="field"><label for="p-team">Team name</label><input id="p-team" maxlength="48" value="${esc(profile.teamName)}" /></div>
      </div>
      <div class="field"><label for="p-org">School or club</label><input id="p-org" maxlength="48" value="${esc(profile.organisation)}" /></div>
      <div class="form-grid">
        <div class="field"><label for="p-spec">Specialisation</label><select id="p-spec">${specOptions}</select></div>
        <div class="field"><label for="p-years">Years coaching</label><input id="p-years" type="number" min="0" max="70" value="${profile.yearsExperience ?? ""}" /></div>
      </div>
      <div class="field"><label for="p-licence">Certification / licence no.</label><input id="p-licence" maxlength="40" value="${esc(profile.licenceNumber)}" />
        <span class="hint">Stored on your profile only. Nothing here is verified by the app.</span></div>
      <button class="btn btn-primary" style="width:100%" type="submit">Save</button>
    </form>
  </div>
  <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("eye", "green")}<h3>What players see</h3></div>
    <p class="muted">Your name, team, organisation, specialisation, experience and roster size appear in the app's coach directory. Your licence number and email never do.</p>
    <div style="margin-top:16px"><button class="btn btn-ghost" data-action="sign-out">Sign out</button></div>
  </div></div>`;
}

// ---- Actions ----------------------------------------------------------------

function val(root: ParentNode, id: string): string {
  return (root.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(`#${id}`)?.value ?? "").trim();
}

function wireSignOut(root: HTMLElement): void {
  root.querySelectorAll<HTMLButtonElement>("[data-action='sign-out']").forEach((b) =>
    b.onclick = async () => {
      stopLive();
      await auth.signOut();
      go("/");
    },
  );
}

function wireActions(root: HTMLElement, coachUid: string, refresh: () => void): void {
  wireSignOut(root);

  const act = async (action: string, el: HTMLElement) => {
    const player = el.dataset["player"] ?? "";
    const original = el.textContent;
    if (el instanceof HTMLButtonElement) {
      if (el.disabled) return;
      el.disabled = true;
    }
    try {
      if (action === "approve" || action === "decline" || action === "remove") {
        await setMemberStatus(coachUid, player, action === "approve" ? "Active" : "Ended");
      } else if (action === "toggle-session") {
        expandedSession = expandedSession === el.dataset["session"] ? null : (el.dataset["session"] ?? null);
      } else if (action === "send-note") {
        const sessionId = el.dataset["session"] ?? "";
        const text = val(root, `note-${CSS.escape(sessionId)}`);
        if (!text) throw new Error("Write a note before sending.");
        el.textContent = "Sending…";
        (el as HTMLButtonElement).disabled = true;
        await postNote({ id: newId(), coachUid, playerUid: player, sessionId, text: text.slice(0, 800), createdAt: Date.now() });
        expandedSession = null;
      } else if (action === "delete-assignment") {
        await deleteAssignment(el.dataset["id"] ?? "");
      }
    } catch (e) {
      console.error(e);
      const status = root.querySelector("#action-status");
      if (status) status.innerHTML = `<div class="form-error" role="alert">Could not save this change. Check your connection and try again. Your text is still here.</div>`;
      el.textContent = original;
      if (el instanceof HTMLButtonElement) el.disabled = false;
      return;
    }
    refresh();
  };

  root.querySelectorAll<HTMLElement>("[data-action]").forEach((el) => {
    if (el.dataset["action"] === "sign-out") return;
    if (el.dataset["action"] === "toggle-session") {
      el.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          el.click();
        }
      });
    }
    el.addEventListener("click", () => void act(el.dataset["action"] ?? "", el));
  });

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

  const profileForm = root.querySelector<HTMLFormElement>("#profile-form");
  profileForm?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const button = profileForm.querySelector("button");
    if (button) button.disabled = true;
    try {
      const [current, roster] = await Promise.all([getProfile(coachUid), getRoster(coachUid)]);
      const years = Number.parseInt(val(root, "p-years"), 10);
      await saveProfile(
        coachUid,
        {
          ...current,
          name: val(root, "p-name"),
          teamName: val(root, "p-team"),
          organisation: val(root, "p-org"),
          specialisation: val(root, "p-spec") || null,
          yearsExperience: Number.isFinite(years) ? Math.min(70, Math.max(0, years)) : null,
          licenceNumber: val(root, "p-licence"),
        },
        roster.filter((m) => m.status === "Active").length,
      );
      const saved = root.querySelector("#profile-saved");
      if (saved) saved.innerHTML = `<div class="note-bubble" style="margin:0 0 14px">Saved.</div>`;
    } catch (err) {
      console.error(err);
      const saved = root.querySelector("#profile-saved");
      if (saved) saved.innerHTML = `<div class="form-error" role="alert">Could not save your profile. Please try again.</div>`;
    }
    if (button) button.disabled = false;
  });
}
