import { gsap } from "gsap";
import { icon } from "../lib/icons";

// Illustration coordinates follow the supplied 736 × 920 photo, not a live pose model.
const joints = [
  [149, 390], [252, 394], [78, 355], [350, 359], [110, 305], [427, 324],
  [184, 550], [252, 551], [143, 682], [270, 673], [47, 797], [263, 797],
  [38, 808], [247, 821], [65, 826], [294, 827],
] as const;
const connections = [[0, 1], [0, 2], [2, 4], [1, 3], [3, 5], [0, 6], [1, 7],
  [6, 7], [6, 8], [8, 10], [7, 9], [9, 11], [10, 12], [12, 14], [10, 14],
  [11, 13], [13, 15], [11, 15]] as const;

export function heroSession(): string {
  const skeleton = `<svg class="pose-skeleton" viewBox="0 0 736 920" fill="none" aria-hidden="true">
    <g class="pose-connections">${connections.map(([a, b]) => `<line x1="${joints[a][0]}" y1="${joints[a][1]}" x2="${joints[b][0]}" y2="${joints[b][1]}"/>`).join("")}</g>
    <g class="pose-joints">${joints.map(([x, y]) => `<circle cx="${x}" cy="${y}" r="5"/>`).join("")}</g>
  </svg>`;

  return `<figure class="analysis-visual session-demo" aria-label="Serving technique coaching demonstration">
    <div class="session-photo">
      <img src="/assets/volleyball-serving.jpg" alt="Volleyball player preparing an overhead serve with an illustrative pose skeleton aligned to her arms, torso, and legs" width="736" height="920" fetchpriority="high" />
      ${skeleton}
      <div class="session-photo-label"><span class="session-dot"></span> Pose overlay <small>DEMO</small></div>
      <span class="session-corner session-corner-tl" aria-hidden="true"></span><span class="session-corner session-corner-br" aria-hidden="true"></span>
      <div class="session-photo-footer"><span>Overhand serve</span><span>Frame 014</span></div>
    </div>
    <div class="session-insights">
      <div class="session-heading"><span class="session-dot"></span><span>Coaching session</span></div>
      <div class="session-score-card">
        <div class="session-panel-label">${icon("chart")} Form score</div>
        <div class="session-score"><svg viewBox="0 0 100 100" aria-hidden="true"><circle class="session-ring-track" cx="50" cy="50" r="43"/><circle class="session-ring-value" cx="50" cy="50" r="43" pathLength="100"/></svg><strong>92<span>%</span></strong></div>
        <p>Strong foundation</p>
      </div>
      <div class="session-metric-card"><div class="session-panel-label">${icon("alignment")} Body alignment <strong>94%</strong></div><div class="session-meter"><i style="--meter-fill:.94"></i></div></div>
      <div class="session-cue-card"><div class="session-panel-label">${icon("message")} Coach cue <span data-cue-count>01 / 03</span></div><strong data-cue-title>Load with balance.</strong><p data-cue-text>Keep your weight controlled as you prepare to contact the ball.</p></div>
    </div>
    <figcaption class="session-controls">
      <div class="session-controls-top"><button type="button" class="session-play" data-demo-play aria-pressed="true">${icon("play")}<span>Pause demo</span></button><span class="session-clock" data-demo-time>00:00 / 00:09</span><button type="button" class="session-overlay-toggle" data-demo-overlay aria-pressed="true">${icon("pose")}<span>Skeleton on</span></button></div>
      <div class="session-timeline" aria-hidden="true"><i></i></div>
    </figcaption>
  </figure>`;
}

export function mountHeroSession(root: HTMLElement): () => void {
  const demo = root.querySelector<HTMLElement>(".session-demo");
  if (!demo) return () => {};
  const play = demo.querySelector<HTMLButtonElement>("[data-demo-play]")!;
  const overlay = demo.querySelector<HTMLButtonElement>("[data-demo-overlay]")!;
  const skeleton = demo.querySelector<SVGElement>(".pose-skeleton")!;
  const progress = demo.querySelector<HTMLElement>(".session-timeline i")!;
  const clock = demo.querySelector<HTMLElement>("[data-demo-time]")!;
  const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
  const cues = [
    ["Load with balance.", "Keep your weight controlled as you prepare to contact the ball."],
    ["Reach through contact.", "Extend your hitting arm and meet the ball above your shoulder."],
    ["Finish the movement.", "Let your arm follow through and bring your weight forward."],
  ] as const;
  let phase = 0;
  const showCue = (index: number) => {
    const cue = cues[index] ?? cues[0];
    phase = index;
    demo.querySelector("[data-cue-count]")!.textContent = `0${index + 1} / 03`;
    demo.querySelector("[data-cue-title]")!.textContent = cue[0];
    demo.querySelector("[data-cue-text]")!.textContent = cue[1];
  };
  const state = { seconds: 0 };
  const timeline = gsap.timeline({ repeat: -1, paused: true });
  timeline.call(() => showCue(0), [], 0)
    .to(progress, { scaleX: 1, duration: 9, ease: "none", startAt: { scaleX: 0 } }, 0)
    .to(state, { seconds: 9, duration: 9, ease: "none", startAt: { seconds: 0 }, onUpdate: () => {
      clock.textContent = `00:0${Math.min(9, Math.floor(state.seconds))} / 00:09`;
    } }, 0)
    .call(() => showCue(1), [], 3).call(() => showCue(2), [], 6);

  const setPlayLabel = () => {
    const playing = !timeline.paused() && !motion.matches;
    play.setAttribute("aria-pressed", String(playing));
    const glyph = play.querySelector("svg");
    if (glyph) glyph.outerHTML = icon(playing ? "pause" : "play");
    play.querySelector("span")!.textContent = motion.matches ? "Next cue" : playing ? "Pause demo" : "Play demo";
    demo.classList.toggle("is-playing", playing);
  };
  const onPlay = () => {
    if (motion.matches) showCue((phase + 1) % cues.length);
    else timeline.paused(!timeline.paused());
    setPlayLabel();
  };
  const onOverlay = () => {
    const enabled = overlay.getAttribute("aria-pressed") !== "true";
    overlay.setAttribute("aria-pressed", String(enabled));
    overlay.querySelector("span")!.textContent = enabled ? "Skeleton on" : "Skeleton off";
    skeleton.style.visibility = enabled ? "visible" : "hidden";
  };
  const onPreference = () => {
    timeline.pause();
    setPlayLabel();
  };
  const observer = new IntersectionObserver(([entry]) => {
    if (!entry?.isIntersecting) { timeline.pause(); setPlayLabel(); }
  });
  observer.observe(demo);
  play.addEventListener("click", onPlay);
  overlay.addEventListener("click", onOverlay);
  motion.addEventListener("change", onPreference);
  if (!motion.matches) timeline.play();
  setPlayLabel();
  return () => {
    timeline.kill();
    observer.disconnect();
    play.removeEventListener("click", onPlay);
    overlay.removeEventListener("click", onOverlay);
    motion.removeEventListener("change", onPreference);
  };
}
