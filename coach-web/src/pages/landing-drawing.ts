import { animate, stagger, svg, utils } from "animejs";

/* anime.js owns line drawing and number counting on the landing page; GSAP owns
   scroll, text and layout motion. Keeping them on different elements means the two
   engines never write the same property. */

type Running = { revert: () => unknown };

export interface Drawings {
  /** Draw stroked shapes in order. Shapes are hidden from the moment this is prepared. */
  prepare: (elements: SVGGeometryElement[]) => (options?: { duration?: number; gap?: number }) => void;
  /** Count a number up to the value already in the markup. */
  count: (element: Element, value: number, delay?: number) => void;
  /** Pop small marks in, one after another. */
  pop: (elements: Element[], delay?: number) => void;
  dispose: () => void;
}

export function createDrawings(): Drawings {
  const running: Running[] = [];
  const restores: (() => void)[] = [];
  const keep = <T extends Running>(item: T): T => { running.push(item); return item; };

  const prepare: Drawings["prepare"] = (elements) => {
    if (!elements.length) return () => {};
    // createDrawable writes these attributes; put the originals back when drawing ends.
    const names = ["pathLength", "stroke-dasharray", "stroke-dashoffset", "stroke-linecap"];
    const saved = elements.map((element) => names.map((name) => element.getAttribute(name)));
    const restore = () => elements.forEach((element, index) => names.forEach((name, slot) => {
      const value = saved[index]?.[slot] ?? null;
      if (value === null) element.removeAttribute(name); else element.setAttribute(name, value);
    }));
    const drawables = svg.createDrawable(elements);
    utils.set(drawables, { draw: "0 0" });
    restores.push(restore);
    return ({ duration = 900, gap = 90 } = {}) => {
      keep(animate(drawables, {
        draw: ["0 0", "0 1"],
        duration,
        delay: stagger(gap),
        ease: "inOutQuad",
        onComplete: restore,
      }));
    };
  };

  const count: Drawings["count"] = (element, value, delay = 0) => {
    const text = element.firstChild;
    if (!text || text.nodeType !== Node.TEXT_NODE) return;
    const original = text.nodeValue;
    const state = { value: 0 };
    text.nodeValue = "0";
    restores.push(() => { text.nodeValue = original; });
    keep(animate(state, {
      value,
      delay,
      duration: 1300,
      ease: "outExpo",
      onUpdate: () => { text.nodeValue = String(Math.round(state.value)); },
      onComplete: () => { text.nodeValue = original; },
    }));
  };

  const pop: Drawings["pop"] = (elements, delay = 0) => {
    if (!elements.length) return;
    const clear = () => elements.forEach((element) => {
      (element as SVGElement).style.removeProperty("transform");
      (element as SVGElement).style.removeProperty("opacity");
    });
    restores.push(clear);
    keep(animate(elements, {
      scale: [0, 1],
      opacity: [0, 1],
      duration: 520,
      delay: stagger(70, { start: delay }),
      ease: "outBack(1.7)",
      onComplete: clear,
    }));
  };

  return {
    prepare, count, pop,
    dispose: () => {
      running.splice(0).reverse().forEach((item) => item.revert());
      restores.splice(0).forEach((restore) => restore());
    },
  };
}
