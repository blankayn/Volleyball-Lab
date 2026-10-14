import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { footer, topbar } from "../lib/ui";

gsap.registerPlugin(ScrollTrigger);

let landingContext: ReturnType<typeof gsap.context> | null = null;
let landingMedia: ReturnType<typeof gsap.matchMedia> | null = null;
let removeParallax: (() => void) | null = null;

export function renderLanding(root: HTMLElement): void {
  document.title = "Volleyball Lab — AI-powered coaching";
  root.innerHTML = `${topbar(false, "portal")}
  <main>
    <section class="hero" aria-labelledby="hero-title">
      <div class="hero-inner">
        <div class="hero-copy">
          <p class="eyebrow">AI-powered coaching</p>
          <h1 id="hero-title"><span class="hero-line">See the form.</span><span class="hero-line">Understand the</span><span class="hero-line">movement.</span><em class="hero-line">Coach better.</em></h1>
          <p class="lead">Volleyball Lab helps coaches review player sessions with AI pose tracking, rep counts, and performance scores. See what they see, write feedback, and help every athlete improve.</p>
          <div class="hero-cta">
            <a class="btn btn-primary" href="#/register">Become a coach <span aria-hidden="true">→</span></a>
            <a class="btn btn-ghost" href="#how-it-works"><span class="play-icon" aria-hidden="true">▶</span> See how it works</a>
          </div>
          <div class="hero-points" aria-label="Portal features"><span><i>✓</i> AI pose analysis</span><span><i>✓</i> Session history</span><span><i>✓</i> Coach feedback</span></div>
        </div>
        <div class="analysis-visual" aria-label="Volleyball player movement analysis preview">
          <img src="/assets/coach-analysis-hero.png" alt="Volleyball athlete receiving a ball with pose analysis markers" />
          <div class="analysis-score glass-panel"><span class="panel-label"><b class="mini-play">▶</b> Form score</span><div class="score-ring"><strong>92<span>%</span></strong></div><small>Great form!</small></div>
          <div class="analysis-metrics">
            <div class="metric glass-panel"><span>◫</span><div><b>Shoulder alignment</b><i><u style="width:94%"></u></i></div><strong>94%</strong></div>
            <div class="metric glass-panel"><span>∠</span><div><b>Elbow angle</b><i><u style="width:88%"></u></i></div><strong>88%</strong></div>
            <div class="coach-cue glass-panel"><span>▢</span><div><b>Coach cue</b><p>Keep your shoulders low and out in front.</p></div></div>
          </div>
          <div class="play-session glass-panel"><span class="mini-play">▶</span> Play session <b>1.0×</b></div>
        </div>
      </div>
      <div class="feature-ribbon wrap" id="features">
        <article class="feature-card"><div class="feature-icon feature-orange">⌁</div><div><h2>AI pose analysis</h2><p>Turn player videos into actionable insights with advanced AI.</p><a href="#about" aria-label="Learn about AI pose analysis">→</a></div><div class="mini-pose" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i></div></article>
        <article class="feature-card"><div class="feature-icon feature-violet">▥</div><div><h2>Session review</h2><p>See reps, scores and trends over time for every athlete.</p><a href="#about" aria-label="Learn about session review">→</a></div><svg class="mini-chart" viewBox="0 0 130 62" aria-hidden="true"><path d="M4 56 C18 44 20 20 38 34 S63 50 76 28 S103 37 126 4"/><path class="chart-fill" d="M4 56 C18 44 20 20 38 34 S63 50 76 28 S103 37 126 4 V62 H4 Z"/></svg></article>
        <article class="feature-card"><div class="feature-icon feature-blue">▣</div><div><h2>Coach feedback</h2><p>Give clear, focused feedback athletes can act on.</p><a href="#about" aria-label="Learn about coach feedback">→</a></div><div class="mini-comments" aria-hidden="true"><span></span><span></span><span></span></div></article>
      </div>
    </section>
    <section class="steps-section" id="how-it-works"><div class="wrap">
      <div class="section-heading"><div><p class="eyebrow">How it works</p><h2>Three steps to a connected team</h2></div><p>From practice to progress, Volleyball Lab makes it simple to coach, track, and support your athletes.</p></div>
      <div class="steps-flow">
        <article class="step-card"><div><b>01</b><span>♟</span></div><h3>Register the team</h3><p>Create your coach account and welcome your athletes in minutes.</p></article><i class="step-arrow" aria-hidden="true">→</i>
        <article class="step-card"><div><b>02</b><span>▷</span></div><h3>Review player sessions</h3><p>Athletes capture practice sessions on their phones. You see the data.</p></article><i class="step-arrow" aria-hidden="true">→</i>
        <article class="step-card"><div><b>03</b><span>↗</span></div><h3>Assign feedback and improve</h3><p>Write feedback, assign next drills, and track progress over time.</p></article>
      </div>
    </div></section>
    <section class="results-section" id="about"><div class="wrap results-grid">
      <div class="results-copy"><p class="eyebrow">Built for real coaches</p><h2>From movement to measurable feedback</h2><p>Our analysis turns every rep into clear insight on technique, consistency, and progress—so you can focus on helping your athletes grow.</p><a class="text-link" href="#/register">Start coaching with Volleyball Lab →</a></div>
      <div class="result-preview"><img src="/assets/coach-analysis-hero.png" alt="Analyzed volleyball receiving session" /><div class="result-sheet"><div class="result-top"><span>Receive analysis</span><b>#14</b></div><div class="result-main"><div class="result-ring">89%</div><div><strong>Overall score</strong><small>Strong and consistent</small></div></div><div class="result-bars"><span>Approach <i><u style="width:87%"></u></i><b>87%</b></span><span>Arm swing <i><u style="width:92%"></u></i><b>92%</b></span><span>Body alignment <i><u style="width:90%"></u></i><b>90%</b></span></div><div class="result-note"><b>Coach note</b><span>“Strong platform. Keep your hips lower through contact.”</span></div></div></div>
    </div></section>
    <section class="final-cta"><div class="wrap"><p class="eyebrow">Ready for the next point?</p><h2>Bring your team into focus.</h2><p>Create your coach account and turn every recorded session into a clear next step.</p><a class="btn btn-primary" href="#/register">Become a coach →</a></div></section>
  </main>${footer()}`;

  root.querySelectorAll<HTMLAnchorElement>('a[href^="#"]:not([href^="#/"])').forEach((link) => {
    link.addEventListener("click", (event) => {
      const target = document.querySelector(link.getAttribute("href") ?? "");
      if (!target) return;
      event.preventDefault();
      const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      target.scrollIntoView({ behavior: reducedMotion ? "auto" : "smooth", block: "start" });
    });
  });

  setupLandingAnimations(root);
}

export function disposeLanding(): void {
  removeParallax?.();
  removeParallax = null;
  landingMedia?.revert();
  landingMedia = null;
  landingContext?.revert();
  landingContext = null;
}

function setupLandingAnimations(root: HTMLElement): void {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;

  landingContext = gsap.context(() => {
    const intro = gsap.timeline({ defaults: { ease: "power3.out" } });
    intro
      .from(".topbar .brand, .topbar .site-nav a, .topbar nav:last-child > *", {
        y: -18,
        opacity: 0,
        duration: 0.55,
        stagger: 0.06,
      })
      .from(".hero-copy .eyebrow", { x: -24, opacity: 0, duration: 0.5 }, 0.15)
      .from(".hero-line", { y: 48, opacity: 0, rotateX: -16, duration: 0.75, stagger: 0.09 }, 0.23)
      .from(".hero-copy .lead", { y: 24, opacity: 0, duration: 0.65 }, 0.52)
      .from(".hero-cta > *", { y: 20, opacity: 0, duration: 0.55, stagger: 0.1 }, 0.64)
      .from(".hero-points span", { y: 14, opacity: 0, duration: 0.45, stagger: 0.08 }, 0.78)
      .from(".analysis-visual", {
        clipPath: "inset(8% 8% 8% 8% round 28px)",
        opacity: 0,
        duration: 1.05,
        clearProps: "clipPath,opacity",
      }, 0.2)
      .from(".analysis-score, .analysis-metrics > *, .play-session", {
        y: 20,
        opacity: 0,
        duration: 0.5,
        stagger: 0.08,
        clearProps: "transform,opacity",
      }, 0.82);

    gsap.from(".feature-card", {
      scrollTrigger: { trigger: ".feature-ribbon", start: "top 88%", once: true },
      y: 50,
      opacity: 0,
      duration: 0.7,
      stagger: 0.12,
      ease: "power3.out",
      clearProps: "transform,opacity",
    });
    gsap.from(".section-heading > *", {
      scrollTrigger: { trigger: ".section-heading", start: "top 86%", once: true },
      y: 34,
      opacity: 0,
      duration: 0.7,
      stagger: 0.12,
      ease: "power3.out",
    });
    gsap.from(".step-card", {
      scrollTrigger: { trigger: ".steps-flow", start: "top 84%", once: true },
      y: 42,
      opacity: 0,
      duration: 0.65,
      stagger: 0.1,
      ease: "back.out(1.2)",
      clearProps: "transform,opacity",
    });
    gsap.from(".step-arrow", {
      scrollTrigger: { trigger: ".steps-flow", start: "top 84%", once: true },
      opacity: 0,
      duration: 0.5,
      delay: 0.2,
      clearProps: "opacity",
    });
    gsap.from(".results-copy > *", {
      scrollTrigger: { trigger: ".results-section", start: "top 78%", once: true },
      y: 34,
      opacity: 0,
      duration: 0.7,
      stagger: 0.1,
      ease: "power3.out",
      clearProps: "transform,opacity",
    });
    gsap.from(".result-preview", {
      scrollTrigger: { trigger: ".results-section", start: "top 78%", once: true },
      y: 42,
      scale: 0.96,
      opacity: 0,
      duration: 0.9,
      ease: "power3.out",
      clearProps: "transform,opacity",
    });
    gsap.from(".result-bars u", {
      scrollTrigger: { trigger: ".result-sheet", start: "top 82%", once: true },
      scaleX: 0,
      transformOrigin: "left center",
      duration: 0.8,
      stagger: 0.12,
      ease: "power2.out",
    });
    gsap.from(".final-cta .wrap > *", {
      scrollTrigger: { trigger: ".final-cta", start: "top 84%", once: true },
      y: 30,
      opacity: 0,
      duration: 0.65,
      stagger: 0.1,
      ease: "power3.out",
      clearProps: "transform,opacity",
    });
  }, root);

  landingMedia = gsap.matchMedia();
  landingMedia.add("(min-width: 1081px) and (prefers-reduced-motion: no-preference)", () => {
    const heroVisual = root.querySelector(".analysis-visual");
    if (!heroVisual) return;
    const floatTrigger = { trigger: heroVisual, start: "top bottom", end: "bottom top", toggleActions: "play pause resume pause" };
    gsap.to(root.querySelector(".analysis-score"), { y: -8, duration: 3.1, delay: 1.6, repeat: -1, yoyo: true, ease: "sine.inOut", scrollTrigger: floatTrigger });
    gsap.to(root.querySelector(".analysis-metrics"), { y: 7, duration: 3.8, delay: 1.8, repeat: -1, yoyo: true, ease: "sine.inOut", scrollTrigger: floatTrigger });
    gsap.to(root.querySelector(".play-session"), { y: -5, duration: 2.7, delay: 1.7, repeat: -1, yoyo: true, ease: "sine.inOut", scrollTrigger: floatTrigger });
  });

  window.requestAnimationFrame(() => {
    if (root.isConnected) ScrollTrigger.refresh();
  });

  const visual = root.querySelector<HTMLElement>(".analysis-visual");
  const image = visual?.querySelector<HTMLImageElement>("img");
  if (!visual || !image || window.matchMedia("(pointer: coarse)").matches) return;

  gsap.set(image, { scale: 1.035 });
  const moveX = gsap.quickTo(image, "x", { duration: 0.8, ease: "power3.out" });
  const moveY = gsap.quickTo(image, "y", { duration: 0.8, ease: "power3.out" });
  const onMove = (event: PointerEvent) => {
    const bounds = visual.getBoundingClientRect();
    moveX(((event.clientX - bounds.left) / bounds.width - 0.5) * 14);
    moveY(((event.clientY - bounds.top) / bounds.height - 0.5) * 10);
  };
  const onLeave = () => { moveX(0); moveY(0); };
  visual.addEventListener("pointermove", onMove);
  visual.addEventListener("pointerleave", onLeave);
  removeParallax = () => {
    visual.removeEventListener("pointermove", onMove);
    visual.removeEventListener("pointerleave", onLeave);
    gsap.killTweensOf(image);
    gsap.set(image, { clearProps: "transform" });
  };
}
