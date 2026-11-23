import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { SplitText } from "gsap/SplitText";
import { createDrawings } from "./landing-drawing";

gsap.registerPlugin(ScrollTrigger, SplitText);

/** Mount motion for this landing-page instance; every effect reverts on disposal. */
export function mountLandingMotion(root: HTMLElement): () => void {
  const media = gsap.matchMedia();
  let disposed = false;
  let refreshFrame = 0;

  const refresh = () => {
    cancelAnimationFrame(refreshFrame);
    refreshFrame = requestAnimationFrame(() => {
      if (!disposed && root.isConnected) ScrollTrigger.refresh();
    });
  };

  media.add({
    motion: "(prefers-reduced-motion: no-preference)",
    reduced: "(prefers-reduced-motion: reduce)",
    desktop: "(min-width: 901px) and (hover: hover) and (pointer: fine)",
  }, (mediaContext) => {
    if (mediaContext.conditions?.reduced) return;
    const listeners: (() => void)[] = [];
    const targets = <T extends Element = HTMLElement>(selector: string): T[] =>
      Array.from(root.querySelectorAll<T>(selector));
    const bind = (element: Element, event: string, handler: EventListener) => {
      element.addEventListener(event, handler);
      listeners.push(() => element.removeEventListener(event, handler));
    };

    const drawings = createDrawings();
    const whenSeen = (trigger: Element, start: string, run: () => void) => {
      ScrollTrigger.create({ trigger, start, once: true, onEnter: run });
    };

    const ctx = gsap.context(() => {
      const intro = gsap.timeline({ defaults: { ease: "power3.out" } });
      // A slow device or a throttled tab must never leave the hero half hidden.
      const deadline = window.setTimeout(() => intro.progress(1), 3500);
      listeners.push(() => window.clearTimeout(deadline));
      const enter = (selector: string, vars: gsap.TweenVars, at: number) => {
        const elements = targets(selector);
        if (elements.length) intro.from(elements, { duration: 0.7, clearProps: "transform,opacity", ...vars }, at);
      };

      enter(".topbar .brand, .topbar nav > a", { y: -15, opacity: 0, stagger: 0.045, duration: 0.5 }, 0);
      enter(".hero-copy .eyebrow, .hero-kicker-index", { y: 15, opacity: 0, stagger: 0.07 }, 0.12);
      const title = root.querySelector<HTMLElement>("#hero-title");
      if (title) {
        // Characters rise from behind each line. Reverting restores kerning and markup.
        const split = SplitText.create(title, { type: "words,chars", aria: "auto" });
        intro.from(split.chars, { yPercent: 112, duration: 0.75, stagger: 0.014, ease: "power4.out",
          onComplete: () => { split.revert(); } }, 0.08);
      }
      enter(".hero-copy .lead", { y: 22, opacity: 0 }, 0.48);
      enter(".hero-cta > *, .hero-points > *", { y: 18, opacity: 0, stagger: 0.065, duration: 0.6 }, 0.62);
      enter(".hero-side-note", { opacity: 0, duration: 0.8 }, 0.85);

      enter(".session-demo", { opacity: 0, duration: 0.5, clearProps: "opacity" }, 0.05);
      const photo = root.querySelector<HTMLElement>(".session-photo");
      if (photo) {
        intro.from(photo, {
          clipPath: "inset(12% 0 12% 0 round 14px)",
          opacity: 0,
          duration: 1,
          clearProps: "clipPath,opacity",
        }, 0.25);
      }
      enter(".session-insights > *, .session-controls", { y: 22, opacity: 0, stagger: 0.09, duration: 0.65 }, 0.64);

      const skeletonLines = targets<SVGLineElement>(".pose-connections line");
      if (skeletonLines.length) {
        const lengths = skeletonLines.map((line) => line.getTotalLength());
        intro.fromTo(skeletonLines, {
          strokeDasharray: (index: number) => lengths[index] ?? 1,
          strokeDashoffset: (index: number) => lengths[index] ?? 1,
        }, {
          strokeDashoffset: 0,
          duration: 0.65,
          stagger: 0.025,
          ease: "power2.inOut",
          clearProps: "strokeDasharray,strokeDashoffset",
        }, 0.65);
      }
      enter(".pose-joints circle", { opacity: 0, stagger: 0.025, duration: 0.35 }, 0.8);
      enter(".session-ring-value", {
        strokeDashoffset: 100,
        duration: 1.4,
        ease: "power2.out",
        clearProps: "strokeDashoffset",
      }, 0.8);
      enter(".session-meter i", { scaleX: 0, transformOrigin: "left center", duration: 1 }, 0.9);

      const reveal = (triggerSelector: string, selector: string, stagger = 0.09) => {
        const trigger = root.querySelector(triggerSelector);
        const elements = targets(selector);
        if (!trigger || !elements.length) return;
        gsap.from(elements, {
          scrollTrigger: { trigger, start: "top 88%", once: true },
          y: 36,
          opacity: 0,
          duration: 0.75,
          stagger,
          ease: "power3.out",
          clearProps: "transform,opacity",
        });
      };
      reveal(".feature-ribbon", ".feature-card", 0.11);
      reveal(".section-heading", ".section-heading > *");
      reveal(".steps-flow", ".step-card", 0.12);
      reveal(".results-section", ".results-copy > *, .result-preview", 0.1);
      reveal(".final-cta", ".final-cta .wrap > *");

      const score = root.querySelector(".session-score > strong");
      if (score) drawings.count(score, 92, 800);
      for (const court of targets<SVGElement>(".court-lines")) {
        const draw = drawings.prepare(Array.from(court.querySelectorAll<SVGGeometryElement>("path")));
        whenSeen(court, "top 92%", () => draw({ duration: 1300, gap: 110 }));
      }
      const ribbon = root.querySelector(".feature-ribbon");
      targets(".feature-card").forEach((card, index) => {
        const draw = drawings.prepare(Array.from(card.querySelectorAll<SVGGeometryElement>(".art-draw path")));
        // Same trigger as the card reveal, so the marks reset while the card is still hidden.
        if (ribbon) whenSeen(ribbon, "top 88%", () => {
          draw({ duration: 800, gap: 110 });
          drawings.pop(Array.from(card.querySelectorAll(".art-bubble, .art-dots circle")), 180 + index * 140);
          card.querySelectorAll("[data-count]").forEach((node) =>
            drawings.count(node, Number(node.getAttribute("data-count")), 300));
        });
      });
      const spike = root.querySelector(".feature-art-spike img");
      if (spike && ribbon) gsap.from(spike, { clipPath: "inset(0 100% 0 0)", scale: 1.14, duration: 1.1, delay: 0.15,
        ease: "power3.out", clearProps: "clipPath,transform", scrollTrigger: { trigger: ribbon, start: "top 88%", once: true } });
      const ring = root.querySelector<SVGElement>(".result-ring-value");
      const ringLabel = root.querySelector(".result-ring > strong");
      if (ring) {
        gsap.from(ring, { strokeDashoffset: 100, duration: 1.3, ease: "power2.out", clearProps: "strokeDashoffset",
          scrollTrigger: { trigger: ring, start: "top 86%", once: true,
            onEnter: () => { if (ringLabel) drawings.count(ringLabel, 89); } } });
      }

      const progress = root.querySelector(".scroll-progress i");
      if (progress) gsap.fromTo(progress, { scaleX: 0 }, { scaleX: 1, ease: "none",
        scrollTrigger: { start: 0, end: "max", scrub: 0.3 } });

      // Four identical sets, so moving by exactly one set loops without a visible seam. The
      // browser drives it in real time, so dropped frames do not slow it down.
      const strip = root.querySelector<HTMLElement>(".performance-strip");
      const track = strip?.querySelector<HTMLElement>(".performance-track");
      if (strip && track) {
        const loop = track.animate([{ transform: "translateX(0)" }, { transform: "translateX(-25%)" }],
          { duration: Math.max(12_000, (track.scrollWidth / 4 / 85) * 1000), iterations: Infinity });
        listeners.push(() => loop.cancel());
        const settle = gsap.to(loop, { playbackRate: 1, duration: 0.9, ease: "power2.out", paused: true });
        let held = false;
        const update = () => { if (held || !ScrollTrigger.isInViewport(strip)) loop.pause(); else loop.play(); };
        // Scrolling adds speed, then the strip eases back to its own pace.
        ScrollTrigger.create({ trigger: strip, start: "top bottom", end: "bottom top", onToggle: update,
          onUpdate: (self) => {
            const boost = 1 + Math.min(Math.abs(self.getVelocity()) / 500, 4);
            if (boost > loop.playbackRate) { loop.playbackRate = boost; settle.invalidate().restart(); }
          } });
        bind(strip, "pointerenter", () => { held = true; update(); });
        bind(strip, "pointerleave", () => { held = false; update(); });
      }

      mountFlight(root, listeners);
      for (const section of targets("#features, #how-it-works, #about")) {
        const link = root.querySelector(`.site-nav a[href="#${section.id}"]`);
        if (link) ScrollTrigger.create({ trigger: section, start: "top 45%", end: "bottom 45%",
          onToggle: ({ isActive }) => { link.classList.toggle("is-current", isActive); } });
      }
      reveal(".landing-footer", ".footer-main > *, .footer-bottom", 0.08);

      const resultBars = targets(".result-bars u");
      const resultSheet = root.querySelector(".result-sheet");
      if (resultBars.length && resultSheet) {
        gsap.from(resultBars, {
          scrollTrigger: { trigger: resultSheet, start: "top 84%", once: true },
          scaleX: 0,
          transformOrigin: "left center",
          duration: 0.9,
          stagger: 0.12,
          ease: "power2.out",
          clearProps: "transform",
        });
      }

      // Animate inner symbols, leaving card reveals and layout transforms independent.
      for (const card of targets(".step-card")) {
        const symbol = card.querySelector<SVGElement>(":scope > div > span .ui-icon");
        if (!symbol) continue;
        const hover = gsap.to(symbol, { y: -3, rotation: -5, scale: 1.08, duration: 0.24, ease: "power2.out", paused: true });
        const enterCard = () => hover.play();
        const leaveCard = () => hover.reverse();
        bind(card, "pointerenter", enterCard);
        bind(card, "pointerleave", leaveCard);
        bind(card, "focusin", enterCard);
        bind(card, "focusout", leaveCard);
      }
      for (const link of targets(".hero-cta a, .feature-card a, .footer-invite a")) {
        const symbol = link.querySelector<SVGElement>(".ui-icon");
        if (!symbol) continue;
        const hover = gsap.to(symbol, { x: 4, duration: 0.22, ease: "power2.out", paused: true });
        bind(link, "pointerenter", () => hover.play());
        bind(link, "pointerleave", () => hover.reverse());
        bind(link, "focus", () => hover.play());
        bind(link, "blur", () => hover.reverse());
      }

      if (mediaContext.conditions?.desktop) {
        for (const button of targets(".hero-cta .btn-primary, .final-cta .btn")) {
          const pullX = gsap.quickTo(button, "x", { duration: 0.45, ease: "power3.out" });
          const pullY = gsap.quickTo(button, "y", { duration: 0.45, ease: "power3.out" });
          bind(button, "pointermove", ((event: PointerEvent) => {
            const bounds = button.getBoundingClientRect();
            pullX((event.clientX - bounds.left - bounds.width / 2) * 0.18);
            pullY((event.clientY - bounds.top - bounds.height / 2) * 0.26);
          }) as EventListener);
          bind(button, "pointerleave", () => { pullX(0); pullY(0); });
        }
      }

      if (photo && mediaContext.conditions?.desktop) {
        const moveX = gsap.quickTo(photo, "x", { duration: 0.7, ease: "power3.out" });
        const moveY = gsap.quickTo(photo, "y", { duration: 0.7, ease: "power3.out" });
        const tilt = gsap.quickTo(photo, "rotation", { duration: 0.8, ease: "power3.out" });
        bind(photo, "pointermove", ((event: PointerEvent) => {
          const bounds = photo.getBoundingClientRect();
          const x = (event.clientX - bounds.left) / bounds.width - 0.5;
          const y = (event.clientY - bounds.top) / bounds.height - 0.5;
          moveX(x * 7);
          moveY(y * 5);
          tilt(x * 0.8);
        }) as EventListener);
        bind(photo, "pointerleave", () => { moveX(0); moveY(0); tilt(0); });
      }
    }, root);

    refresh();
    return () => {
      listeners.forEach((remove) => remove());
      ctx.revert();
      drawings.dispose();
    };
  }, root);

  // Fonts can change section heights after the first render; photo dimensions are reserved.
  void document.fonts.ready.then(() => { if (!disposed) refresh(); });
  return () => {
    disposed = true;
    cancelAnimationFrame(refreshFrame);
    media.revert();
  };
}

/** Move the ball through pass, set and attack with scroll, lighting each step it reaches. */
function mountFlight(root: HTMLElement, cleanups: (() => void)[]): void {
  const flight = root.querySelector<SVGSVGElement>(".ball-flight");
  const flow = root.querySelector<HTMLElement>(".steps-flow");
  const ball = flight?.querySelector<SVGGElement>(".flight-ball");
  // Hidden on narrow screens, where the stacked cards keep their static look.
  if (!flight || !flow || !ball || !flight.getClientRects().length) return;
  const paths = Array.from(flight.querySelectorAll<SVGPathElement>(".flight-guide path"));
  const contacts = Array.from(flight.querySelectorAll(".flight-contact"));
  const cards = Array.from(flow.querySelectorAll(".step-card"));
  const lengths = paths.map((path) => path.getTotalLength());
  const total = lengths.reduce((sum, length) => sum + length, 0);
  const resting = ball.getAttribute("transform") ?? "";

  const draw = (progress: number) => {
    let travelled = progress * total;
    let placed = false;
    paths.forEach((path, index) => {
      const length = lengths[index] ?? 0;
      const inside = Math.max(0, Math.min(length, travelled));
      if (!placed && (travelled <= length || index === paths.length - 1)) {
        const point = path.getPointAtLength(inside);
        ball.setAttribute("transform", `translate(${point.x} ${point.y}) rotate(${progress * 720})`);
        placed = true;
      }
      travelled -= length;
      const touched = travelled >= -1;
      contacts[index]?.classList.toggle("is-touched", touched);
      cards[index]?.classList.toggle("is-touched", touched);
    });
  };

  flow.classList.add("is-live");
  draw(0);
  const state = { progress: 0 };
  gsap.to(state, {
    progress: 1, ease: "none", onUpdate: () => draw(state.progress),
    scrollTrigger: { trigger: flight, start: "top 78%", end: "bottom 22%", scrub: 0.5 },
  });
  cleanups.push(() => {
    flow.classList.remove("is-live");
    ball.setAttribute("transform", resting);
    [...contacts, ...cards].forEach((element) => element.classList.remove("is-touched"));
  });
}
