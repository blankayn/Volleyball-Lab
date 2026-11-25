/* Keeping buttons honest while something is saved. */

/**
 * Runs page refreshes so that the newest one wins.
 *
 * A refresh asked for after a save (not `live`) must finish: it is what replaces the
 * form and its busy button. Live updates that arrive during it are put off until it is
 * done. They used to cancel it and then, finding the coach had typed something, stop
 * themselves, which left "Sending…" on screen for good.
 */
export function createRefresher(run: (live: boolean, current: () => boolean) => Promise<boolean>): (live?: boolean) => Promise<boolean> {
  let newest = 0, asked = 0, putOff = false;
  const refresh = async (live = false): Promise<boolean> => {
    if (live && asked > 0) { putOff = true; return false; }
    const mine = ++newest;
    if (!live) asked++;
    try { return await run(live, () => mine === newest); }
    finally {
      if (!live) asked--;
      if (asked === 0 && putOff) { putOff = false; void refresh(true); }
    }
  };
  return refresh;
}

/** Tells the coach when a save is slow. Firestore waits for a connection instead of failing. */
export async function withSlowNotice<T>(work: Promise<T>, onSlow: () => void, afterMs = 8000): Promise<T> {
  const timer = setTimeout(onSlow, afterMs);
  try { return await work; } finally { clearTimeout(timer); }
}

export const SLOW_SAVE = "Still waiting for a connection. Keep this page open; it finishes by itself when you are back online.";
