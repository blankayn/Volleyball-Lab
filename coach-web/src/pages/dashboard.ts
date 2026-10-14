import { auth } from "../lib/firebase";
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
import { dueLabel, esc, fullDate, timeAgo } from "../lib/format";
import { appPath, go } from "../lib/router";
import { avatar, emptyState, errorCard, footer, iconChip, scoreBar, spinner, topbar } from "../lib/ui";

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

let dashboardVersion = 0;
export function disposeDashboard(): void {
  dashboardVersion++;
  stopLive();
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
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths}</svg>`;
}

function sidebarLinks(tab: Tab, playerUid: string | null): string {
  const link = (id: Tab, label: string, icon: string, href = appPath(id)) =>
    `<a class="dash-nav-link${tab === id ? " active" : ""}" href="${href}"${tab === id ? ' aria-current="page"' : ""}>${navIcon(icon)}<span>${esc(label)}</span></a>`;
  return `${link("overview", PAGE_LABELS.overview, '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>')}
    ${link("queue", PAGE_LABELS.queue, '<rect x="4" y="3" width="16" height="18" rx="2"/><path d="m8 9 1.5 1.5L12 8M14 9h3M8 16h9"/>')}
    ${link("roster", PAGE_LABELS.roster, '<circle cx="9" cy="8" r="3"/><path d="M3 21v-3a6 6 0 0 1 12 0v3M16 5a3 3 0 0 1 0 6M18 14a5 5 0 0 1 3 4v3"/>')}
    ${link("assignments", PAGE_LABELS.assignments, '<rect x="5" y="5" width="14" height="16" rx="2"/><rect x="9" y="2" width="6" height="5" rx="1"/><path d="M9 12h6M9 16h4"/>')}
    ${link("account", PAGE_LABELS.account, '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>')}
    ${playerUid ? `<div class="dash-nav-context"><p class="dash-nav-label">Selected player</p>${link("player", memberShort(playerUid), '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>', appPath("player", playerUid))}</div>` : ""}`;
}

function wireSidebar(root: HTMLElement): void {
  const toggle = root.querySelector<HTMLButtonElement>("#dash-menu-toggle");
  const sidebar = root.querySelector<HTMLElement>("#dashboard-sidebar");
  if (!toggle || !sidebar) return;
  const setOpen = (open: boolean) => {
    toggle.setAttribute("aria-expanded", String(open));
    sidebar.classList.toggle("is-open", open);
    if (!open && sidebar.contains(document.activeElement)) toggle.focus();
  };
  toggle.addEventListener("click", () => setOpen(toggle.getAttribute("aria-expanded") !== "true"));
  const closeOnEscape = (event: KeyboardEvent) => {
    if (event.key === "Escape" && toggle.getAttribute("aria-expanded") === "true") {
      setOpen(false);
      toggle.focus();
    }
  };
  toggle.addEventListener("keydown", closeOnEscape);
  sidebar.addEventListener("keydown", closeOnEscape);
  sidebar.addEventListener("click", (event) => {
    if ((event.target as Element).closest("a")) setOpen(false);
  });
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
  document.title = "Dashboard — Volleyball Lab Coach Portal";
  const activeTab = (["overview", "queue", "roster", "assignments", "account", "player"] as Tab[]).includes(tab as Tab)
    ? (tab as Tab)
    : "overview";

  root.innerHTML = `${topbar(true, "portal")}<div class="wrap dash-shell">
    <div class="dash-mobile-nav">
      <button class="btn btn-ghost btn-sm" id="dash-menu-toggle" aria-controls="dashboard-sidebar" aria-expanded="false">${navIcon('<path d="M4 6h16M4 12h16M4 18h16"/>')}Pages</button>
      <span>${PAGE_LABELS[activeTab]}</span>
    </div>
    <aside class="dash-sidebar" id="dashboard-sidebar" aria-label="Dashboard sidebar">
      <div class="dash-sidebar-heading"><span class="dash-workspace-mark">${navIcon('<path d="M12 3 3 8v8l9 5 9-5V8l-9-5ZM3 8l9 5 9-5M12 13v8"/>')}</span><div><strong>Coach workspace</strong><span>Your team, together</span></div></div>
      <p class="dash-nav-label">Workspace</p>
      <nav class="dash-nav" aria-label="Coach dashboard pages">${sidebarLinks(activeTab, playerUid)}</nav>
      <div class="dash-sidebar-footer"><button class="dash-sign-out" data-action="sign-out">${navIcon('<path d="M9 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M14 8l4 4-4 4M8 12h13"/>')}<span>Sign out</span></button></div>
    </aside>
    <main class="dash-main" id="dashboard-content">
      <div class="dash-head">
        <div><p class="eyebrow" style="margin:0">Coach dashboard</p>
        <h1>${PAGE_LABELS[activeTab]}</h1><p class="dash-team-name">Your team</p></div>
      </div>
      <div id="action-status" role="status" aria-live="polite"></div>
      <div id="dash-body">${spinner("Loading your team")}</div>
    </main>
  </div>${footer()}`;
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
      // Update the selected player's label without replacing the sidebar shell.
      const nav = root.querySelector(".dash-nav");
      if (nav) nav.innerHTML = sidebarLinks(activeTab, playerUid);
      const html = await viewFor(activeTab, coachUid, data, playerUid);
      if (version !== dashboardVersion || attempt !== request || !body.isConnected || (live && dirty)) return;
      body.innerHTML = html;
      dirty = false;
      const teamName = root.querySelector(".dash-team-name");
      if (teamName) teamName.textContent = data.profileName ? `${data.profileName}’s team` : "Your team";
      if (activeTab === "player" && playerUid) {
        const heading = root.querySelector(".dash-head h1");
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

function viewOverview(data: DashData): string {
  const active = activeRoster(data.roster);
  const pending = data.submissions.filter((s) => s.reviewedAt === null);
  const requests = data.roster.filter((m) => m.status === "Pending");
  const weekAgo = Date.now() - 7 * 86_400_000;
  const setsWeek = [...data.sessionsByPlayer.values()]
    .flat()
    .filter((s) => s.startedAt >= weekAgo).length;
  const teamForms = active
    .map((m) => meanForm(data.sessionsByPlayer.get(m.playerUid) ?? []))
    .filter((f): f is number => f !== null);
  const teamForm = teamForms.length > 0
    ? Math.round(teamForms.reduce((a, b) => a + b, 0) / teamForms.length)
    : null;

  const focus =
    requests.length > 0
      ? { eyebrow: "Get started", title: `${requests.length} waiting to join`, body: "Review the requests on your roster. Nothing else needs you first.", href: appPath("roster"), cta: "Review requests" }
      : pending.length > 0
        ? { eyebrow: "Review queue", title: `${pending.length} session${pending.length === 1 ? "" : "s"} waiting`, body: "Your players sent these for review. Open one to score it and write back.", href: appPath("queue"), cta: "Open queue" }
        : { eyebrow: "All clear", title: "Nothing waiting", body: "Your queue is empty. New submissions from your roster land here.", href: appPath("roster"), cta: "View roster" };

  const queuePreview = pending.slice(0, 4).map((s) => {
    const name = memberName(data.roster, s.playerUid);
    return `<a class="row-item" href="${appPath("player", s.playerUid)}">${avatar(name)}
      <span style="flex:1"><strong>${esc(name)}</strong><br /><span class="muted">${esc(sessionTitle(data, s.sessionId))} · ${timeAgo(s.submittedAt)}</span></span>
      <span class="dot"></span></a>`;
  }).join("");

  return `<div class="card-dark" style="margin-top:14px">
      <p class="eyebrow">${focus.eyebrow}</p>
      <h2 style="font-size:26px;margin-bottom:8px">${esc(focus.title)}</h2>
      <p style="margin:0 0 18px;max-width:60ch">${esc(focus.body)}</p>
      <a class="btn btn-primary" href="${focus.href}">${esc(focus.cta)}</a>
    </div>
    <div class="stat-strip" style="margin-top:14px">
      <div class="stat"><b>${active.length}</b><span>Players</span></div>
      <div class="stat"><b>${pending.length}</b><span>To review</span></div>
      <div class="stat"><b>${teamForm ?? "—"}</b><span>Team form</span></div>
      <div class="stat"><b>${setsWeek}</b><span>Sets this week</span></div>
    </div>
    <div class="page-grid">
      <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("▤", "indigo")}<h3>Review queue</h3></div>
        ${pending.length === 0
          ? emptyState("▤", "Nothing waiting", "Sessions your players submit for review land here. They choose what to send; nothing is shared automatically.")
          : `<div class="row-list">${queuePreview}</div>`}
      </div>
      <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("○", "rust")}<h3>Roster</h3></div>
        ${active.length === 0
          ? emptyState("○", "No players yet", "Once a player asks to join and you approve them, their submitted sessions show up here.")
          : `<div class="row-list">${active.slice(0, 4).map((m) => playerRow(data, m)).join("")}</div>`}
      </div>
    </div>
    <p class="muted" style="font-size:12.5px;margin-top:14px">Connected to the player app through the shared backend — requests, sessions and notes sync both ways. Pose analysis stays on athletes' phones; only scores travel.</p>`;
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
    <span style="color:var(--ink-faint)">›</span></a>`;
}

// ---- Queue ------------------------------------------------------------------

function viewQueue(data: DashData, highlight: string | null): string {
  void highlight;
  const pending = data.submissions.filter((s) => s.reviewedAt === null);
  if (pending.length === 0) {
    return `<div class="card" style="margin-top:14px">${emptyState("▤", "Nothing waiting", "Sessions your players submit for review land here. They choose what to send; nothing is shared automatically.")}</div>`;
  }
  return `<div class="card" style="margin-top:14px">
    <div class="chip-row" style="margin-bottom:8px">${iconChip("▤", "indigo")}<h3 class="section-title">Review queue</h3></div>
    <div class="row-list">${pending.map((s) => {
      const name = memberName(data.roster, s.playerUid);
      return `<a class="row-item" href="${appPath("player", s.playerUid)}">${avatar(name)}
        <span style="flex:1"><strong>${esc(name)}</strong><br />
        <span class="muted">${esc(sessionTitle(data, s.sessionId))} · sent ${timeAgo(s.submittedAt)}</span></span>
        <span class="dot"></span><span style="color:var(--ink-faint)">›</span></a>`;
    }).join("")}</div></div>`;
}

// ---- Roster -----------------------------------------------------------------

function viewRoster(data: DashData): string {
  const requests = data.roster.filter((m) => m.status === "Pending");
  const active = activeRoster(data.roster);
  const past = data.roster.filter((m) => m.status === "Ended");
  return `<div class="page-grid">
    <div class="card span-2"><div class="chip-row" style="margin-bottom:8px">${iconChip("＋", "rust")}<h3 class="section-title">Wants to join ${requests.length > 0 ? `(${requests.length})` : ""}</h3></div>
      ${requests.length === 0
        ? emptyState("＋", "No requests", "Players pick you from their app's Coach tab. Their requests land here for you to approve.")
        : `<div class="row-list">${requests.map((m) => `
          <div class="row-item">${avatar(m.playerName || "Player")}
            <span style="flex:1"><strong>${esc(m.playerName || "Unnamed player")}</strong><br />
            <span class="muted">${esc(m.position ?? "Position not set")} · asked ${timeAgo(m.requestedAt)}</span></span>
            <button class="btn btn-primary btn-sm" data-action="approve" data-player="${esc(m.playerUid)}">Approve</button>
            <button class="btn btn-ghost btn-sm" data-action="decline" data-player="${esc(m.playerUid)}">Decline</button>
          </div>`).join("")}</div>`}
    </div>
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("○", "rust")}<h3 class="section-title">On the team (${active.length})</h3></div>
      ${active.length === 0 ? emptyState("○", "Nobody yet", "Approved players appear here.") : `<div class="row-list">${active.map((m) => playerRow(data, m)).join("")}</div>`}
    </div>
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("—", "indigo")}<h3 class="section-title">Past players</h3></div>
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
    return `<div class="card" style="margin-top:14px">${emptyState("○", "Not on your roster", "This player is not on your team, so there is nothing to show.")}</div>`;
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
    ? emptyState("▦", "No shared sessions", "This player has not submitted a session yet. Only sessions they choose to send appear here.")
    : sessions.map((s) => sessionCard(coachUid, playerUid, s, notes)).join("");

  return `<div style="margin-top:14px"><a href="${appPath("roster")}" style="font-size:13.5px;font-weight:700;color:var(--ink-soft);text-decoration:none">‹ Back to roster</a></div>
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
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("▆", "green")}<h3>By skill</h3></div>
      ${skills.map((s) => `<div style="display:flex;align-items:center;gap:12px;padding:7px 0">
        <span style="flex:1;font-weight:600">${SKILL_LABEL[s.skill]}</span>${scoreBar(s.mean)}</div>`).join("")}
    </div>
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("✚", "violet")}<h3>Drills set</h3></div>
      ${assignments.length === 0 ? `<p class="muted">No drills set yet.</p>` : assignments.slice(0, 4).map((a) => `
        <div style="padding:8px 0;border-top:1px solid var(--line)"><strong>${esc(a.title)}</strong><br />
        <span class="muted">${a.targetSets} × ${a.targetReps} reps · ${esc(a.status)}${a.dueAt ? ` · ${dueLabel(a.dueAt)}` : ""}</span></div>`).join("")}
      <div style="margin-top:10px"><a class="btn btn-ghost btn-sm" href="${appPath("assignments")}">Set a drill</a></div>
    </div>
  </div>

  <div class="card" style="margin-top:14px"><div class="chip-row" style="margin-bottom:8px">${iconChip("▦", "green")}<h3 class="section-title">Shared sessions</h3></div>
    ${sessionCards}</div>

  <div class="card" style="margin-top:14px"><div class="chip-row" style="margin-bottom:8px">${iconChip("✎", "violet")}<h3>Your notes</h3></div>
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
      <span style="color:var(--ink-faint)">${open ? "▾" : "▸"}</span>
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
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("✚", "violet")}<h3 class="section-title">Set a drill</h3></div>
      ${active.length === 0 ? emptyState("✚", "Approve a player first", "A drill needs someone to do it.") : `
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
    <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("▤", "indigo")}<h3 class="section-title">Assigned (${data.assignments.length})</h3></div>
      ${data.assignments.length === 0 ? emptyState("▤", "No drills set", "Write a drill and hand it to one of your players. They see it in their Coach tab with your instructions attached.") : `
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
    <div class="chip-row" style="margin-bottom:12px">${iconChip("○", "rust")}<h3 class="section-title">Team &amp; credentials</h3></div>
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
  <div class="card"><div class="chip-row" style="margin-bottom:8px">${iconChip("◉", "green")}<h3>What players see</h3></div>
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
