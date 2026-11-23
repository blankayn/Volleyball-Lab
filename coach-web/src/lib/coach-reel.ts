import { cueDefinition, formatMeasured, formatTarget } from "./cues";
import { esc } from "./format";

type Point = { x: number; y: number; confidence: number };
type Frame = { timestampMs: number; points: Point[] };
type Issue = { timestampMs: number; repNumber: number; cueId: string; measured: number; score: number; points: number[]; angle?: string };
type Rep = { repNumber: number; startMs: number; endMs: number; angle?: string };
export type ReelClip = { rep: Rep; issue: Issue; startMs: number; endMs: number; freezeMs: number };

/** Mirrors Android CoachReel.build; uses the same privately shared source video. */
export function buildCoachReel(data: unknown): ReelClip[] {
  const review = data as { durationMs?: number; sampleIntervalMs?: number; frames?: Frame[]; issues?: Issue[]; reps?: Rep[] } | null;
  if (!review || !Number.isFinite(review.durationMs) || review.durationMs! <= 0 || !Array.isArray(review.frames) || !Array.isArray(review.issues)) return [];
  const duration = review.durationMs!;
  const frames = review.frames.filter(f => f && Number.isFinite(f.timestampMs) && Array.isArray(f.points)).sort((a, b) => a.timestampMs - b.timestampMs);
  const gap = Math.min(150, (review.sampleIntervalMs ?? 100) / 2 + 25);
  const visible = (issue: Issue) => {
    let low = 0, high = frames.length;
    while (low < high) {
      const mid = (low + high) >>> 1;
      if (frames[mid]!.timestampMs < issue.timestampMs) low = mid + 1; else high = mid;
    }
    const frame = [frames[low], frames[low - 1]].filter((f): f is Frame => !!f)
      .sort((a, b) => Math.abs(a.timestampMs - issue.timestampMs) - Math.abs(b.timestampMs - issue.timestampMs))[0];
    return !!frame && frame.points.length === 33 && Math.abs(frame.timestampMs - issue.timestampMs) <= gap && issue.points.length > 0 &&
      issue.points.every(i => {
        const p = frame.points[i];
        return Number.isInteger(i) && !!p && Number.isFinite(p.x) && Number.isFinite(p.y) && Number.isFinite(p.confidence) &&
          p.x >= 0 && p.x <= 1 && p.y >= 0 && p.y <= 1 && p.confidence >= 0.5;
      });
  };
  const issues = review.issues.filter(i => i && Number.isFinite(i.timestampMs) && Number.isInteger(i.repNumber) &&
    Number.isFinite(i.score) && Number.isFinite(i.measured) && typeof i.cueId === "string" && Array.isArray(i.points));
  let reps = Array.isArray(review.reps) ? review.reps.filter(r => r && Number.isInteger(r.repNumber) && Number.isFinite(r.startMs) && Number.isFinite(r.endMs)) : [];
  if (!reps.length) {
    reps = [...new Set(issues.map(i => i.repNumber))].flatMap(repNumber => {
      const measured = issues.filter(i => i.repNumber === repNumber && visible(i));
      if (!measured.length) return [];
      const angles = [...new Set(measured.map(i => i.angle ?? "Unknown"))];
      return [{ repNumber, startMs: Math.max(0, Math.min(...measured.map(i => i.timestampMs)) - 1200),
        endMs: Math.min(duration, Math.max(...measured.map(i => i.timestampMs)) + 1200), angle: angles.length === 1 ? angles[0]! : "Unknown" }];
    });
  }
  const severity = (a: Issue, b: Issue) => a.score - b.score || (cueDefinition(b.cueId)?.weight ?? 0) - (cueDefinition(a.cueId)?.weight ?? 0);
  const candidates = reps.flatMap(rep => {
    const issue = issues.filter(i => i.repNumber === rep.repNumber && i.score < 0.8 && i.timestampMs >= rep.startMs && i.timestampMs <= rep.endMs && visible(i)).sort(severity)[0];
    if (!issue) return [];
    const startMs = Math.max(0, rep.startMs, issue.timestampMs - 4000);
    const endMs = Math.min(duration, rep.endMs, startMs + 8000);
    return endMs > startMs ? [{ rep, issue, startMs, endMs, freezeMs: Math.max(startMs, Math.min(endMs, issue.timestampMs)) }] : [];
  }).sort((a, b) => severity(a.issue, b.issue) || a.freezeMs - b.freezeMs);
  const selected: ReelClip[] = [];
  const add = (clip: ReelClip | undefined) => {
    if (clip && selected.length < 6 && !selected.some(c => c.rep.repNumber === clip.rep.repNumber || c.startMs < clip.endMs && clip.startMs < c.endMs)) selected.push(clip);
  };
  ["Front", "Side"].forEach(angle => add(candidates.find(c => c.rep.angle === angle)));
  candidates.forEach(add);
  return selected.sort((a, b) => a.issue.score - b.issue.score || a.freezeMs - b.freezeMs);
}

export function attachCoachReel(video: HTMLVideoElement, host: HTMLElement, data: unknown): () => void {
  const clips = buildCoachReel(data);
  host.hidden = false;
  host.innerHTML = `<h4>Coach Reel</h4><p class="muted">Selected measured corrections from this recording. Automated practice guidance; add your professional assessment in Coach Feedback below.</p>`;
  if (!clips.length) {
    host.innerHTML += `<p>No eligible correction clips in the saved pose data. You can still review the full recording and provide Coach Feedback.</p>`;
    return () => { host.replaceChildren(); host.hidden = true; };
  }
  host.innerHTML += `<div class="chip-row"><button type="button" class="btn btn-primary btn-sm" data-reel-play>Play Coach Reel</button><button type="button" class="btn btn-ghost btn-sm" data-reel-full>Full Recording</button></div><div class="chip-row" style="margin-top:8px">${clips.map((c, i) => `<button type="button" class="btn btn-ghost btn-sm" data-reel-clip="${i}">${esc(c.rep.angle && c.rep.angle !== "Unknown" ? c.rep.angle + " · " : "")}Rep ${c.rep.repNumber}</button>`).join("")}</div><p data-reel-comment role="status" aria-live="polite"></p>`;
  let current = -1, running = false, disposed = false;
  let animation = 0, frozen = -1;
  let hold: ReturnType<typeof setTimeout> | undefined;
  const comment = host.querySelector<HTMLElement>("[data-reel-comment]")!;
  const release = () => { clearTimeout(hold); hold = undefined; };
  const stop = () => { running = false; release(); window.cancelAnimationFrame(animation); };
  const show = (index: number) => {
    const clip = clips[index]!;
    const cue = cueDefinition(clip.issue.cueId);
    comment.textContent = cue ? `${cue.label}: ${formatMeasured(clip.issue.measured, cue.unit)} (practice range ${formatTarget(cue.target, cue.unit)}). ${cue.tip}` : "This measured position was outside the practice range.";
    video.dispatchEvent(new CustomEvent("reelhighlight", { detail: clip.issue.points }));
    host.querySelectorAll<HTMLButtonElement>("[data-reel-clip]").forEach(b => b.setAttribute("aria-pressed", String(Number(b.dataset.reelClip) === index)));
  };
  const play = () => { void video.play().catch(() => { stop(); comment.textContent = "Playback could not start. Use the video controls to retry."; }); };
  const start = (index: number) => {
    if (disposed) return;
    release(); frozen = -1;
    current = index; show(index);
    video.currentTime = clips[index]!.startMs / 1000;
    play();
  };
  const update = () => {
    if (!running || disposed) return;
    const clip = clips[current];
    if (clip && !video.seeking && video.currentTime * 1000 >= clip.endMs - 30) {
      if (current + 1 < clips.length) start(current + 1);
      else { stop(); video.pause(); comment.textContent += " Coach Reel complete."; return; }
    } else if (clip && !video.seeking && !video.paused && frozen !== current && video.currentTime * 1000 >= clip.freezeMs) {
      // Hold on the measured moment, as the Android reel does, so the note can be read.
      frozen = current;
      video.pause();
      video.currentTime = clip.freezeMs / 1000;
      hold = setTimeout(() => { hold = undefined; if (running && !disposed) play(); }, 2500);
      return;
    }
    animation = window.requestAnimationFrame(update);
  };
  const onClick = (event: Event) => {
    const button = (event.target as Element).closest<HTMLButtonElement>("button");
    if (!button) return;
    stop();
    if (button.hasAttribute("data-reel-full")) {
      current = -1; video.pause(); video.currentTime = 0; comment.textContent = "Full recording selected. Use the video controls to play or scrub.";
      video.dispatchEvent(new CustomEvent("reelhighlight", { detail: [] }));
      host.querySelectorAll("[data-reel-clip]").forEach(b => b.setAttribute("aria-pressed", "false"));
    } else if (button.hasAttribute("data-reel-play") || button.dataset.reelClip !== undefined) {
      running = true; start(Number(button.dataset.reelClip ?? 0)); animation = window.requestAnimationFrame(update);
    }
  };
  // Pause keeps the current clip; play resumes its bounds. Scrubbing outside it returns to full recording.
  const onPause = () => { window.cancelAnimationFrame(animation); };
  const onPlay = () => { release(); if (running) { window.cancelAnimationFrame(animation); animation = window.requestAnimationFrame(update); } };
  const onEnded = () => {
    if (!running) return;
    if (current + 1 < clips.length) start(current + 1);
    else { stop(); comment.textContent += " Coach Reel complete."; }
  };
  const onRepSeek = () => {
    stop(); current = -1; comment.textContent = "";
    video.dispatchEvent(new CustomEvent("reelhighlight", { detail: [] }));
    host.querySelectorAll("[data-reel-clip]").forEach(b => b.setAttribute("aria-pressed", "false"));
  };
  const onSeek = () => {
    const clip = clips[current];
    if (clip && (video.currentTime * 1000 < clip.startMs - 100 || video.currentTime * 1000 > clip.endMs + 100)) {
      stop(); video.dispatchEvent(new CustomEvent("reelhighlight", { detail: [] }));
      comment.textContent = "Full recording selected after seeking outside the clip.";
    }
  };
  host.addEventListener("click", onClick);
  video.addEventListener("repseek", onRepSeek);
  video.addEventListener("pause", onPause); video.addEventListener("play", onPlay); video.addEventListener("seeked", onSeek); video.addEventListener("ended", onEnded);
  return () => {
    disposed = true; stop(); host.removeEventListener("click", onClick); video.removeEventListener("repseek", onRepSeek);
    video.removeEventListener("pause", onPause); video.removeEventListener("play", onPlay); video.removeEventListener("seeked", onSeek); video.removeEventListener("ended", onEnded);
    video.dispatchEvent(new CustomEvent("reelhighlight", { detail: [] }));
    host.replaceChildren(); host.hidden = true;
  };
}
