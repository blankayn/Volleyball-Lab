/** A small, consistent set of court and coaching symbols. */
const paths = {
  arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  play: '<path d="m9 5 10 7-10 7V5Z"/>',
  pause: '<path d="M8 5v14M16 5v14"/>',
  pose: '<circle cx="12" cy="4" r="2"/><path d="m4 9 8-2 8 2M12 7v7m0 0-5 7m5-7 5 7"/><circle cx="4" cy="9" r="1"/><circle cx="20" cy="9" r="1"/>',
  chart: '<path d="M4 4v16h16M8 15l4-5 4 2 4-7"/>',
  message: '<path d="M4 4h16v13H9l-5 4V4Z"/><path d="M8 8h8m-8 4h5"/>',
  team: '<circle cx="9" cy="7" r="3"/><path d="M3 21v-3a6 6 0 0 1 12 0v3M17 4a3 3 0 0 1 0 6m4 11v-3a6 6 0 0 0-4-5"/>',
  alignment: '<path d="M4 6h16M4 18h16M12 3v18"/><circle cx="12" cy="12" r="3"/>',
  angle: '<path d="m6 4 2 15 12-7M8 13a6 6 0 0 1 5 3"/><circle cx="8" cy="19" r="1.5"/>',
  court: '<rect x="3" y="4" width="18" height="16" rx="1"/><path d="M12 4v16M7 4v16M17 4v16"/>',
  review: '<rect x="4" y="3" width="16" height="18" rx="2"/><path d="m8 9 1.5 1.5L12 8M14 9h3M8 16h9"/>',
  clipboard: '<rect x="5" y="5" width="14" height="16" rx="2"/><rect x="9" y="2" width="6" height="5" rx="1"/><path d="M9 12h6M9 16h4"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
  userPlus: '<circle cx="8" cy="8" r="3"/><path d="M2 21v-3a6 6 0 0 1 12 0v3m4-14v8m-4-4h8"/>',
  archive: '<path d="M4 7h16v14H4zM3 3h18v4H3zm6 8h6"/>',
  session: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="m10 8 6 4-6 4V8Z"/>',
  note: '<path d="M13 4H4v16h16v-9M8 16l1-4L18 3l3 3-9 9-4 1Z"/>',
  eye: '<path d="M2 12s3-7 10-7 10 7 10 7-3 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
  eyeOff: '<path d="m3 3 18 18M10 5a12 12 0 0 1 12 7 18 18 0 0 1-4 5M6 6a18 18 0 0 0-4 6s3 7 10 7a13 13 0 0 0 5-1M10 10a3 3 0 0 0 4 4"/>',
  lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 6 9 7 9-7"/>',
  chevronRight: '<path d="m9 5 7 7-7 7"/>',
  chevronDown: '<path d="m5 9 7 7 7-7"/>',
  chevronLeft: '<path d="m15 5-7 7 7 7"/>',
  shield: '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z"/><path d="m8 12 3 3 5-6"/>',
} as const;

export type IconName = keyof typeof paths;

export function icon(name: IconName): string {
  return `<svg class="ui-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">${paths[name]}</svg>`;
}
