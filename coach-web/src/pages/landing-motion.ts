import { gsap } from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

gsap.registerPlugin(ScrollTrigger);

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

    const ctx = gsap.context(() => {
      const intro = gsap.timeline({ defaults: { ease: "power3.out" } });
      const enter = (selector: string, vars: gsap.TweenVars, at: number) => {
        const elements = targets(selector);
        if (elements.length) intro.from(elements, { duration: 0.7, clearProps: "transform,opacity", ...vars }, at);
      };

      enter(".topbar .brand, .topbar nav > a", { y: -15, opacity: 0, stagger: 0.045, duration: 0.5 }, 0);
      enter(".hero-copy .eyebrow, .hero-kicker-index", { y: 15, opacity: 0, stagger: 0.07 }, 0.12);
      enter(".hero-line", { y: 56, opacity: 0, rotateX: -8, stagger: 0.09, duration: 0.85 }, 0.2);
      enter(".hero-copy .lead", { y: 22, opacity: 0 }, 0.48);
      enter(".hero-cta > *, .hero-points > *", { y: 18, opacity: 0, stagger: 0.065, duration: 0.6 }, 0.62);
      enter(".hero-side-note", { opacity: 0, duration: 0.8 }, 0.85);

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
      reveal(".performance-strip", ".performance-word", 0.06);
      reveal(".feature-ribbon", ".feature-card", 0.11);
      reveal(".section-heading", ".section-heading > *");
      reveal(".steps-flow", ".step-card", 0.12);
      reveal(".results-section", ".results-copy > *, .result-preview", 0.1);
      reveal(".final-cta", ".final-cta .wrap > *");
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
      for (const card of targets(".feature-card, .step-card")) {
        const symbol = card.querySelector<SVGElement>(".feature-icon .ui-icon, :scope > div > span .ui-icon");
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
