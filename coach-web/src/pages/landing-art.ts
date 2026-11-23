/* Decorative line art for the landing page. All of it is complete and visible as
   static markup; motion only draws it in, so nothing depends on JavaScript timing. */

/** A court seen in perspective, used behind the hero and the closing statement. */
export function courtLines(className: string): string {
  return `<svg class="court-lines ${className}" viewBox="0 0 1440 420" preserveAspectRatio="xMidYMax slice" fill="none" aria-hidden="true" focusable="false">
    <path d="M430 60H1010"/><path d="M340 140H1100"/><path d="M262 210H1178"/><path d="M132 326H1308"/><path d="M20 426H1420"/>
    <path d="M430 60 20 426"/><path d="M1010 60 1420 426"/>
  </svg>`;
}

/** A whiteboard-style play over the step cards: pass, set, attack, in a 1200 x 220 box. */
const flightSegments = [
  "M-30 40Q80 50 200 186",
  "M200 186Q400 -50 600 176",
  "M600 176Q800 -90 1000 60",
  "M1000 60L1216 200",
] as const;

const contacts = [
  [200, 186, "Pass"], [600, 176, "Set"], [1000, 60, "Attack"],
] as const;

export function ballFlight(): string {
  return `<svg class="ball-flight" viewBox="0 0 1200 220" fill="none" aria-hidden="true" focusable="false">
    <g class="flight-net"><path d="M1084 112V220M1070 112h28M1070 130h28M1070 148h28M1070 166h28M1070 184h28M1070 202h28"/></g>
    <g class="flight-guide">${flightSegments.map((d) => `<path d="${d}"/>`).join("")}</g>
    ${contacts.map(([x, y, label], index) => `<g class="flight-contact" data-contact="${index}"><circle cx="${x}" cy="${y}" r="7"/><text x="${x}" y="${index === 2 ? y - 22 : y - 26}" text-anchor="middle">${label}</text></g>`).join("")}
    <g class="flight-ball" transform="translate(1216 200)"><circle r="15"/><path d="M-14 -5C-4 -3 5 3 9 12M-8 -13C-2 -5 8 -2 15 -2M-12 9C-6 4 -4 -2 -4 -14"/></g>
  </svg>`;
}

const spike = `<img src="/assets/volleyball-spike-silhouette.jpg" alt="Silhouette of a volleyball player jumping to spike" width="736" height="736" loading="lazy" decoding="async" />`;

const chart = `<svg viewBox="0 0 320 150" fill="none" aria-hidden="true" focusable="false">
  <path class="art-floor" d="M24 38H296M24 72H296M24 106H296M24 140H296"/>
  <g class="art-draw"><path d="M28 120C62 116 70 92 104 94S150 70 188 74 250 40 290 30"/></g>
  <g class="art-dots"><circle cx="28" cy="120" r="4"/><circle cx="104" cy="94" r="4"/><circle cx="188" cy="74" r="4"/><circle cx="290" cy="30" r="5"/></g>
  <text x="290" y="98" text-anchor="end">Example score <tspan data-count="89">89</tspan></text>
</svg>`;

const chat = `<svg viewBox="0 0 320 150" fill="none" aria-hidden="true" focusable="false">
  <g class="art-bubble"><rect x="30" y="20" width="190" height="52" rx="12"/><path class="art-line" d="M48 40H196M48 54H150"/></g>
  <g class="art-bubble art-bubble-reply"><rect x="120" y="88" width="172" height="46" rx="12"/><text x="158" y="116">Next drill set</text></g>
  <g class="art-draw"><path d="m134 112 6 6 11-13"/></g>
</svg>`;

export function featureArt(kind: "spike" | "chart" | "chat"): string {
  return `<div class="feature-art feature-art-${kind}">${{ spike, chart, chat }[kind]}</div>`;
}
