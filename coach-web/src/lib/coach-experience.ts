import { gsap } from "gsap";
import { PREFERENCES_EVENT, getPreferences } from "./preferences";

export const coachMotionAllowed = (): boolean => !getPreferences().reduceMotion
  && !window.matchMedia("(prefers-reduced-motion: reduce)").matches;

/** Motion enhances a completed render; it never delays loading or interaction. */
export function wireCoachExperience(root: HTMLElement, enter: boolean): () => void {
  const disposers: (() => void)[] = [];
  const animations = new Set<gsap.core.Tween>();
  if (enter && coachMotionAllowed()) {
    const targets = Array.from(root.children).slice(0, 8);
    const tween = gsap.fromTo(targets, { y: 12, opacity: 0 }, {
      y: 0, opacity: 1, duration: .32, stagger: .035, ease: "power2.out", clearProps: "transform,opacity",
    });
    animations.add(tween);
    disposers.push(() => { tween.kill(); gsap.set(targets, { clearProps: "transform,opacity" }); });
  }

  root.querySelectorAll<HTMLDetailsElement>("[data-session-details]").forEach(details => {
    const summary = details.querySelector<HTMLElement>("summary")!;
    const content = details.querySelector<HTMLElement>(".session-detail")!;
    const mount = () => {
      const template = content.querySelector<HTMLTemplateElement>("template[data-session-template]");
      if (template) content.replaceChildren(template.content.cloneNode(true));
    };
    if (details.open) mount();
    let desiredOpen = details.open;
    let tween: gsap.core.Tween | undefined;
    const settle = () => {
      tween?.kill();
      details.open = desiredOpen;
      gsap.set(content, { clearProps: "height,opacity,overflow" });
    };
    const toggle = (event: MouseEvent) => {
      event.preventDefault();
      const height = details.open ? content.getBoundingClientRect().height : 0;
      desiredOpen = !desiredOpen;
      if (desiredOpen) mount();
      if (!coachMotionAllowed()) { settle(); return; }
      tween?.kill();
      details.open = true;
      gsap.set(content, { height, overflow: "hidden" });
      tween = gsap.to(content, {
        height: desiredOpen ? "auto" : 0, opacity: desiredOpen ? 1 : 0,
        duration: .24, ease: "power2.inOut", onComplete: settle,
      });
      animations.add(tween);
    };
    summary.addEventListener("click", toggle);
    disposers.push(() => { summary.removeEventListener("click", toggle); settle(); });
  });

  root.querySelectorAll<HTMLInputElement>("[data-coach-search]").forEach(input => {
    const region = input.closest<HTMLElement>("[data-search-region]")!;
    const rows = Array.from(region.querySelectorAll<HTMLElement>("[data-search-item]"));
    const apply = () => {
      const query = input.value.trim().toLocaleLowerCase();
      let count = 0;
      rows.forEach(row => { row.hidden = !row.dataset.searchItem!.toLocaleLowerCase().includes(query); if (!row.hidden) count++; });
      const empty = region.querySelector<HTMLElement>("[data-search-empty]");
      if (empty) empty.hidden = count > 0;
      const result = region.querySelector<HTMLElement>("[data-search-count]");
      if (result) result.textContent = query ? `${count} result${count === 1 ? "" : "s"}` : `${rows.length} player${rows.length === 1 ? "" : "s"}`;
    };
    input.addEventListener("input", apply);
    disposers.push(() => input.removeEventListener("input", apply));
    apply();
  });

  const stopMotion = () => {
    if (!coachMotionAllowed()) { animations.forEach(animation => animation.progress(1)); }
  };
  const systemMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  window.addEventListener(PREFERENCES_EVENT, stopMotion);
  systemMotion.addEventListener("change", stopMotion);
  return () => {
    disposers.forEach(dispose => dispose());
    animations.forEach(animation => animation.kill());
    window.removeEventListener(PREFERENCES_EVENT, stopMotion);
    systemMotion.removeEventListener("change", stopMotion);
  };
}
