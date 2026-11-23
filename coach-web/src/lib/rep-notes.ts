import { cueDefinition, formatMeasured, formatTarget } from "./cues";
import { esc } from "./format";

/* Automated notes on the player's recording: which reps were flagged, when, and where on
   the body. Everything comes from the review saved with the video; nothing is inferred here. */

type Issue = { timestampMs: number; repNumber: number; cueId: string; measured: number; score: number; points: number[] };
type Rep = { repNumber: number; startMs: number; endMs: number; formScore?: number | null };
export type RepNote = { atMs: number; repNumber: number; score: number; points: number[]; title: string; detail: string; tip: string };
export type RepReport = { repNumber: number; startMs: number; endMs: number; formScore: number | null; notes: RepNote[] };

/** A cue counts as flagged below this score, matching the Android review. */
const FLAGGED_BELOW = 0.8;
const BEFORE_MS = 350, AFTER_MS = 1300;

function describe(issue: Issue): RepNote {
  const cue = cueDefinition(issue.cueId);
  const base = { atMs: issue.timestampMs, repNumber: issue.repNumber, score: issue.score,
    points: issue.points.filter((i) => Number.isInteger(i) && i >= 0 && i < 33) };
  if (!cue) return { ...base, title: "Outside the practice range", detail: "This measured position was outside the practice range.", tip: "" };
  const [low, high] = cue.target;
  const where = issue.measured < low ? "below" : issue.measured > high ? "above" : "flagged near";
  return { ...base, title: cue.label, tip: cue.tip,
    detail: `${formatMeasured(issue.measured, cue.unit)}, ${where} the practice range (${formatTarget(cue.target, cue.unit)})` };
}

/** Every rep in order, each with its flagged cues, worst first. */
export function buildRepReports(data: unknown): RepReport[] {
  const review = data as { durationMs?: number; issues?: Issue[]; reps?: Rep[] } | null;
  if (!review || !Number.isFinite(review.durationMs) || review.durationMs! <= 0) return [];
  const duration = review.durationMs!;
  const issues = (Array.isArray(review.issues) ? review.issues : []).filter((i) => i && Number.isFinite(i.timestampMs) &&
    i.timestampMs >= 0 && i.timestampMs <= duration && Number.isInteger(i.repNumber) && Number.isFinite(i.score) && i.score < FLAGGED_BELOW &&
    Number.isFinite(i.measured) && typeof i.cueId === "string" && Array.isArray(i.points));
  const reports = new Map<number, RepReport>();
  for (const rep of Array.isArray(review.reps) ? review.reps : []) {
    if (!rep || !Number.isInteger(rep.repNumber) || !Number.isFinite(rep.startMs) || !Number.isFinite(rep.endMs)) continue;
    reports.set(rep.repNumber, { repNumber: rep.repNumber, startMs: Math.max(0, rep.startMs), endMs: Math.min(duration, rep.endMs),
      formScore: Number.isFinite(rep.formScore) ? rep.formScore! : null, notes: [] });
  }
  for (const issue of issues) {
    let report = reports.get(issue.repNumber);
    if (!report) {
      // Older reviews carry no rep boundaries; a flagged rep is still worth listing.
      report = { repNumber: issue.repNumber, startMs: Math.max(0, issue.timestampMs - 1200), endMs: Math.min(duration, issue.timestampMs + 1200), formScore: null, notes: [] };
      reports.set(issue.repNumber, report);
    }
    report.notes.push(describe(issue));
  }
  const ordered = [...reports.values()].sort((a, b) => a.startMs - b.startMs || a.repNumber - b.repNumber);
  ordered.forEach((report) => report.notes.sort((a, b) => a.score - b.score || a.atMs - b.atMs));
  return ordered;
}

/** Notes to show at a playback position: at most two, worst first, from one rep. */
export function notesAt(reports: RepReport[], timeMs: number): RepNote[] {
  const active = reports.flatMap((r) => r.notes).filter((n) => timeMs >= n.atMs - BEFORE_MS && timeMs <= n.atMs + AFTER_MS)
    .sort((a, b) => a.score - b.score);
  return active.filter((n) => n.repNumber === active[0]!.repNumber).slice(0, 2);
}

const clock = (ms: number): string => `${Math.floor(ms / 60_000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, "0")}`;

export function attachRepNotes(video: HTMLVideoElement, stage: HTMLElement, host: HTMLElement, toggle: HTMLInputElement, data: unknown): () => void {
  const reports = buildRepReports(data);
  const duration = (data as { durationMs?: number } | null)?.durationMs ?? 0;
  const flagged = reports.filter((r) => r.notes.length > 0);
  host.hidden = false;
  if (!reports.length) {
    host.innerHTML = `<h4>Rep notes</h4><p class="muted">No reps were saved with this recording, so there is nothing to point out automatically.</p>`;
    return () => { host.replaceChildren(); host.hidden = true; };
  }
  const span = (ms: number) => `${Math.max(0, Math.min(100, (ms / duration) * 100)).toFixed(2)}%`;
  const noteButton = (n: RepNote) => `<button type="button" class="rep-note" data-rep-seek="${n.atMs}"><span class="rep-note-time">${clock(n.atMs)}</span><span><strong>${esc(n.title)}</strong><small>${esc(n.detail)}</small>${n.tip ? `<small>${esc(n.tip)}</small>` : ""}</span></button>`;
  const clean = reports.filter((r) => !r.notes.length);
  host.innerHTML = `<div class="rep-notes-head"><h4>Rep notes</h4><span><b>${flagged.length}</b> of ${reports.length} rep${reports.length === 1 ? "" : "s"} flagged</span></div>
    <p class="muted">Automated measurements from this recording, shown on the video as it plays. Add your own assessment in Coach Feedback below.</p>
    <div class="rep-strip" aria-hidden="true">${reports.map((r) => `<i class="${r.notes.length ? "is-flagged" : ""}" style="left:${span(r.startMs)};width:${span(Math.max(0, r.endMs - r.startMs))}"></i>`).join("")}<b data-rep-playhead></b></div>
    ${flagged.length
      ? `<ol class="rep-list">${flagged.map((r) => `<li><div class="rep-list-head"><strong>Rep ${r.repNumber}</strong>${r.formScore === null ? "" : `<span>Form ${r.formScore}</span>`}</div>${r.notes.map(noteButton).join("")}</li>`).join("")}</ol>`
      : `<p>No reps were flagged by the automated checks. These cover measured posture only.</p>`}
    ${flagged.length && clean.length ? `<p class="muted">No correction flagged: ${clean.map((r) => `Rep ${r.repNumber}`).join(", ")}.</p>` : ""}`;

  const callout = document.createElement("div");
  callout.className = "rep-callout";
  callout.hidden = true;
  callout.setAttribute("aria-hidden", "true"); // The same text is in the list below the video.
  stage.append(callout);
  const playhead = host.querySelector<HTMLElement>("[data-rep-playhead]");
  const buttons = Array.from(host.querySelectorAll<HTMLButtonElement>("[data-rep-seek]"));
  let frame = 0, shown = "";
  const render = () => {
    const time = video.currentTime * 1000;
    if (playhead) playhead.style.left = span(time);
    const active = toggle.checked && !video.seeking ? notesAt(reports, time) : [];
    const key = active.map((n) => `${n.repNumber}:${n.atMs}:${n.title}`).join("|");
    if (key === shown) return;
    shown = key;
    callout.hidden = !active.length;
    callout.innerHTML = active.length ? `<b>Rep ${active[0]!.repNumber}</b>${active.map((n) => `<p><strong>${esc(n.title)}</strong><span>${esc(n.detail)}</span></p>`).join("")}` : "";
    const times = new Set(active.map((n) => String(n.atMs)));
    buttons.forEach((b) => b.classList.toggle("is-now", times.has(b.dataset.repSeek ?? "")));
    video.dispatchEvent(new CustomEvent("repnotes", { detail: active.map((n) => n.points) }));
  };
  const update = () => {
    window.cancelAnimationFrame(frame);
    render();
    if (!video.paused && !video.ended) frame = window.requestAnimationFrame(update);
  };
  const onClick = (event: Event) => {
    const button = (event.target as Element).closest<HTMLButtonElement>("[data-rep-seek]");
    if (!button) return;
    video.dispatchEvent(new CustomEvent("repseek"));
    video.pause();
    video.currentTime = Number(button.dataset.repSeek) / 1000;
    stage.scrollIntoView({ block: "center", behavior: "auto" });
  };
  const events = ["loadedmetadata", "play", "pause", "seeking", "seeked", "ended", "timeupdate"];
  events.forEach((event) => video.addEventListener(event, update));
  toggle.addEventListener("change", update);
  host.addEventListener("click", onClick);
  update();
  return () => {
    window.cancelAnimationFrame(frame);
    events.forEach((event) => video.removeEventListener(event, update));
    toggle.removeEventListener("change", update);
    host.removeEventListener("click", onClick);
    video.dispatchEvent(new CustomEvent("repnotes", { detail: [] }));
    callout.remove();
    host.replaceChildren();
    host.hidden = true;
  };
}
