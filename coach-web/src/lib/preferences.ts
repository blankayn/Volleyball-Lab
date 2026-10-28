export type Theme = "light" | "dark" | "system";
export type Accent = "rust" | "forest" | "plum";
export interface PortalPreferences {
  theme: Theme;
  accent: Accent;
  density: "comfortable" | "compact";
  textSize: "normal" | "large" | "larger";
  highContrast: boolean;
  reduceMotion: boolean;
  enhancedFocus: boolean;
  submissionAlerts: boolean;
  deadlineReminders: boolean;
  trainingNotifications: boolean;
  emailNotifications: boolean;
  photo: string;
  certifications: string;
}

export const PREFERENCES_EVENT = "vl:preferences-changed";
export const defaultPreferences = (): PortalPreferences => ({
  theme: "system", accent: "rust", density: "comfortable", textSize: "normal",
  highContrast: false, reduceMotion: false, enhancedFocus: true,
  submissionAlerts: true, deadlineReminders: true, trainingNotifications: true,
  emailNotifications: false, photo: "", certifications: "",
});

export function validPhoto(photo: unknown): photo is string {
  return typeof photo === "string" && photo.length <= 120_000
    && (photo === "" || /^data:image\/jpeg;base64,[A-Za-z0-9+/]+=*$/.test(photo));
}

export function normalizePreferences(value: unknown): PortalPreferences {
  const defaults = defaultPreferences();
  if (!value || typeof value !== "object") return defaults;
  const data = value as Record<string, unknown>;
  return {
    theme: data.theme === "light" || data.theme === "dark" ? data.theme : "system",
    accent: data.accent === "forest" || data.accent === "plum" ? data.accent : "rust",
    density: data.density === "compact" ? "compact" : "comfortable",
    textSize: data.textSize === "large" || data.textSize === "larger" ? data.textSize : "normal",
    highContrast: data.highContrast === true,
    reduceMotion: data.reduceMotion === true,
    enhancedFocus: data.enhancedFocus !== false,
    submissionAlerts: data.submissionAlerts !== false,
    deadlineReminders: data.deadlineReminders !== false,
    trainingNotifications: data.trainingNotifications !== false,
    emailNotifications: data.emailNotifications === true,
    photo: validPhoto(data.photo) ? data.photo : "",
    certifications: typeof data.certifications === "string" ? data.certifications.slice(0, 1200) : "",
  };
}

let activeUid: string | null = null;
let current = defaultPreferences();
const cacheKey = (uid: string) => `vl.coach.preferences.${uid}`;

export function getPreferences(): PortalPreferences { return { ...current }; }

export function applyPreferences(): void {
  const root = document.documentElement;
  const dark = current.theme === "dark" || (current.theme === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
  root.dataset.portalTheme = dark ? "dark" : "light";
  root.dataset.portalAccent = current.accent;
  root.dataset.portalDensity = current.density;
  root.dataset.portalSize = current.textSize;
  root.dataset.portalContrast = String(current.highContrast);
  root.dataset.portalMotion = current.reduceMotion ? "reduced" : "system";
  root.dataset.portalFocus = String(current.enhancedFocus);
  window.dispatchEvent(new CustomEvent(PREFERENCES_EVENT));
}

export function setPreferenceAccount(uid: string | null): void {
  if (uid === activeUid) return;
  activeUid = uid;
  current = defaultPreferences();
  if (uid) {
    try { current = normalizePreferences(JSON.parse(localStorage.getItem(cacheKey(uid)) ?? "null")); } catch { /* Use defaults. */ }
  }
  applyPreferences();
}

export function updatePreferences(uid: string, preferences: PortalPreferences): void {
  if (uid !== activeUid) return;
  current = normalizePreferences(preferences);
  try { localStorage.setItem(cacheKey(uid), JSON.stringify(current)); } catch { /* Cloud persistence can still succeed. */ }
  applyPreferences();
}

export function watchSystemPreferences(): void {
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyPreferences);
  window.addEventListener("storage", (event) => {
    if (!activeUid || event.key !== cacheKey(activeUid)) return;
    try { current = normalizePreferences(JSON.parse(event.newValue ?? "null")); applyPreferences(); } catch { /* Ignore corrupt cache. */ }
  });
}
