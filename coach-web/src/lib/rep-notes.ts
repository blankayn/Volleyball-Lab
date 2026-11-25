import { cueDefinition, formatMeasured, formatTarget } from "./cues";
import { correctionFor } from "./correction-guide";
import { esc } from "./format";

/* Automated notes on the player's recording: which reps were flagged, when, and where on
   the body. Everything comes from the review saved with the video; nothing is inferred here. */

type Issue = { timestampMs: number; repNumber: number; cueId: string; measured: number; score: number; points: number[] };
type Rep = { repNumber: number; startMs: number; endMs: number; formScore?: number | null; angle?: string };
export type RepNote = { atMs: number; repNumber: number; score: number; points: number[]; title: string; detail: string; measured: string; wrong: string; why: string; fix: string };
export type RepReport = { repNumber: number; startMs: number; endMs: number; formScore: number | null; angle: string | null; notes: RepNote[] };

/** A cue counts as flagged below this score, matching the Android review. */
const FLAGGED_BELOW = 0.8;
const BEFORE_MS = 350, AFTER_MS = 1300;

function describe(issue: Issue): RepNote {
  const cue = cueDefinition(issue.cueId);
  const base = { atMs: issue.timestampMs, repNumber: issue.repNumber, score: issue.score,
    points: issue.points.filter((i) => Number.isInteger(i) && i >= 0 && i < 33) };
  if (!cue) return { ...base, title: "Outside the practice range", detail: "This measured position was outside the practice range.",
    measured: "", wrong: "This measured position was outside the practice range.", why: "", fix: "" };
  const [low, high] = cue.target;
  const where = issue.measured < low ? "below" : issue.measured > high ? "above" : "flagged near";
  const guide = correctionFor(issue.cueId, issue.measured);
  return { ...base, title: cue.label, wrong: guide?.wrong ?? "", why: guide?.why ?? "", fix: guide?.fix ?? cue.tip,
    measured: `Measured ${formatMeasured(issue.measured, cue.unit)}. Practice range ${formatTarget(cue.target, cue.unit)}${guide?.gap ? `, so ${guide.gap}` : ""}.`,
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
      formScore: Number.isFinite(rep.formScore) ? rep.formScore! : null,
      angle: rep.angle === "Front" || rep.angle === "Side" ? rep.angle : null, notes: [] });
  }
  for (const issue of issues) {
    let report = reports.get(issue.repNumber);
    if (!report) {
      // Older reviews carry no rep boundaries; a flagged rep is still worth listing.
      report = { repNumber: issue.repNumber, startMs: Math.max(0, issue.timestampMs - 1200), endMs: Math.min(duration, issue.timestampMs + 1200), formScore: null, angle: null, notes: [] };
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
  const flagged = reports.filter((r) => r.notes.length > 0);
  host.hidden = false;
  if (!reports.length) {
    host.innerHTML = `<h4>Reps</h4><p class="muted">No reps were saved with this recording, so there is nothing to point out automatically.</p>`;
    return () => { host.replaceChildren(); host.hidden = true; };
  }
  const worst = [...flagged].sort((a, b) => a.notes[0]!.score - b.notes[0]!.score)[0] ?? reports[0]!;
  const bothViews = new Set(reports.map((r) => r.angle).filter(Boolean)).size > 1;
  const chip = (r: RepReport) => `<button type="button" class="rep-chip${r.notes.length ? " is-flagged" : ""}" data-rep-pick="${r.repNumber}" aria-pressed="false">Rep ${r.repNumber}<small>${bothViews && r.angle ? `${r.angle} · ` : ""}${r.notes.length ? `${r.notes.length} to fix` : "clear"}</small></button>`;
  host.innerHTML = `<div class="rep-notes-head"><h4>Reps</h4><span>${flagged.length ? `<b>${flagged.length}</b> of ${reports.length} to work on` : `${reports.length} rep${reports.length === 1 ? "" : "s"}, nothing flagged`}</span></div>
    <p class="muted rep-hint">Choose a rep to see it in the video.</p>
    <div class="rep-chips">${reports.map(chip).join("")}</div>
    <div class="rep-detail" data-rep-detail aria-live="polite"></div>
    <p class="muted rep-footnote">Automated posture checks. Add your own assessment in Coach Feedback below.</p>`;
  const detail = host.querySelector<HTMLElement>("[data-rep-detail]")!;
  const picks = Array.from(host.querySelectorAll<HTMLButtonElement>("[data-rep-pick]"));
  let picked = -1;
  const part = (heading: string, text: string) => text ? `<dt>${heading}</dt><dd>${esc(text)}</dd>` : "";
  const note = (n: RepNote, several: boolean) => `<div class="rep-note">
    ${several ? `<button type="button" class="rep-note-title" data-rep-seek="${n.atMs}">${esc(n.title)}<small>${clock(n.atMs)}</small></button>` : `<p class="rep-note-title">${esc(n.title)}<small>${clock(n.atMs)}</small></p>`}
    ${n.measured ? `<p class="rep-measured">${esc(n.measured)}</p>` : ""}
    <dl>${part("What went wrong", n.wrong)}${part("Why it matters", n.why)}${part("How to fix it", n.fix)}</dl></div>`;
  const pick = (repNumber: number) => {
    const report = reports.find((r) => r.repNumber === repNumber);
    if (!report || repNumber === picked) return report;
    picked = repNumber;
    picks.forEach((b) => b.setAttribute("aria-pressed", String(Number(b.dataset.repPick) === repNumber)));
    detail.innerHTML = `<div class="rep-list-head"><strong>Rep ${report.repNumber}${report.angle ? ` · ${report.angle} view` : ""}</strong>${report.formScore === null ? "" : `<span>Form ${report.formScore}</span>`}</div>
      ${report.notes.length ? report.notes.map((n) => note(n, report.notes.length > 1)).join("") : `<p class="muted">Nothing was flagged in this rep.</p>`}<div class="rep-mark" data-rep-mark></div>`;
    host.dispatchEvent(new CustomEvent("reppick", { detail: { repNumber, slot: detail.querySelector("[data-rep-mark]") } }));
    return report;
  };
  // Choosing a rep is what shows it: the video stops on its worst correction, or on its start.
  const show = (ms: number) => {
    video.dispatchEvent(new CustomEvent("repseek"));
    video.pause();
    video.currentTime = ms / 1000;
  };
  pick(worst.repNumber);

  const callout = document.createElement("div");
  callout.className = "rep-callout";
  callout.hidden = true;
  callout.setAttribute("aria-hidden", "true"); // The same text is in the list below the video.
  stage.append(callout);
  let frame = 0, shown = "";
  const render = () => {
    const time = video.currentTime * 1000;
    const playing = reports.find((r) => time >= r.startMs && time <= r.endMs)?.repNumber;
    picks.forEach((b) => b.classList.toggle("is-now", Number(b.dataset.repPick) === playing));
    // While it plays, the explanation follows the video.
    if (playing !== undefined && !video.paused) pick(playing);
    const active = toggle.checked && !video.seeking ? notesAt(reports, time) : [];
    const key = active.map((n) => `${n.repNumber}:${n.atMs}:${n.title}`).join("|");
    if (key === shown) return;
    shown = key;
    callout.hidden = !active.length;
    callout.innerHTML = active.length ? `<b>Rep ${active[0]!.repNumber}</b>${active.map((n) => `<p><strong>${esc(n.title)}</strong><span>${esc(n.detail)}</span></p>`).join("")}` : "";
    video.dispatchEvent(new CustomEvent("repnotes", { detail: active.map((n) => n.points) }));
  };
  const update = () => {
    window.cancelAnimationFrame(frame);
    render();
    if (!video.paused && !video.ended) frame = window.requestAnimationFrame(update);
  };
  const onClick = (event: Event) => {
    const choice = (event.target as Element).closest<HTMLButtonElement>("[data-rep-pick]");
    if (choice) {
      const report = pick(Number(choice.dataset.repPick));
      if (report) show(report.notes[0]?.atMs ?? report.startMs);
      return;
    }
    const button = (event.target as Element).closest<HTMLButtonElement>("[data-rep-seek]");
    if (button) show(Number(button.dataset.repSeek));
  };
  const events = ["loadedmetadata", "play", "pause", "seeking", "seeked", "ended", "timeupdate"];
  events.forEach((event) => video.addEventListener(event, update));
  toggle.addEventListener("change", update);
  host.addEventListener("click", onClick);
  // The reel and the list always show the same rep.
  const onReel = (event: Event) => pick((event as CustomEvent<number>).detail);
  video.addEventListener("reelrep", onReel);
  update();
  return () => {
    window.cancelAnimationFrame(frame);
    events.forEach((event) => video.removeEventListener(event, update));
    toggle.removeEventListener("change", update);
    host.removeEventListener("click", onClick);
    video.removeEventListener("reelrep", onReel);
    video.dispatchEvent(new CustomEvent("repnotes", { detail: [] }));
    callout.remove();
    host.replaceChildren();
    host.hidden = true;
  };
}
