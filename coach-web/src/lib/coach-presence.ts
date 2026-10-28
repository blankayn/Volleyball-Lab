import { collection, deleteDoc, doc, getDocsFromServer, limit, onSnapshot, query, serverTimestamp, setDoc, Timestamp, where } from "firebase/firestore";
import { auth, db } from "./firebase";
import { parseRoute } from "./router";
import { CoachPresencePublisher, PRESENCE_CHECK_MS, resolveCoachPresence, type CoachPresence, type PresenceLease } from "./coach-presence-policy";

let connection: { uid: string; dispose: () => Promise<void> } | null = null;

function coachIsBusy(): boolean {
  const route = parseRoute(location.hash);
  if (route.name !== "app") return false;
  if (route.tab === "player" && (route.sessionId || document.querySelector("[data-session-details][open]"))) return true;
  const focused = document.activeElement;
  return ["questions", "player", "assignments"].includes(route.tab) && focused instanceof HTMLElement &&
    focused.matches("textarea, input, select, [contenteditable='true']");
}

export function setCoachPresenceAccount(uid: string | null): void {
  if (connection?.uid === uid) return;
  void stopCoachPresence();
  if (!uid) return;
  const ref = doc(collection(db, "coachPresence"));
  const publisher = new CoachPresencePublisher(
    () => ({ foreground: document.visibilityState === "visible" && document.hasFocus(), connected: navigator.onLine, busy: coachIsBusy() }),
    async (status, expiresAt) => {
      if (auth.currentUser?.uid !== uid) throw Error("Presence account changed");
      await setDoc(ref, { coachUid: uid, status, updatedAt: serverTimestamp(), expiresAt: Timestamp.fromMillis(expiresAt) });
    }, Date.now, () => performance.now());
  const touch = () => publisher.touch();
  const update = () => { void publisher.tick(); };
  const wake = () => { if (document.visibilityState === "visible" && document.hasFocus()) touch(); else update(); };
  const activityEvents = ["pointerdown", "pointermove", "keydown", "input", "wheel", "touchstart"];
  const statusEvents = ["focus", "blur", "online", "offline", "focusin", "focusout", "hashchange", "toggle"];
  activityEvents.forEach((event) => window.addEventListener(event, touch, { passive: true }));
  statusEvents.forEach((event) => window.addEventListener(event, event === "focus" ? wake : update, true));
  document.addEventListener("visibilitychange", wake);
  const timer = window.setInterval(update, PRESENCE_CHECK_MS);
  const pageHide = (event: PageTransitionEvent) => {
    void stopCoachPresence();
    if (event.persisted) window.addEventListener("pageshow", pageShow, { once: true });
  };
  const pageShow = () => { if (auth.currentUser?.uid === uid && !connection && parseRoute(location.hash).name === "app") setCoachPresenceAccount(uid); };
  window.addEventListener("pagehide", pageHide);
  connection = { uid, dispose: async () => {
    publisher.stop(); clearInterval(timer);
    activityEvents.forEach((event) => window.removeEventListener(event, touch));
    statusEvents.forEach((event) => window.removeEventListener(event, event === "focus" ? wake : update, true));
    document.removeEventListener("visibilitychange", wake);
    window.removeEventListener("pagehide", pageHide);
    window.removeEventListener("pageshow", pageShow);
    try { await deleteDoc(ref); } catch { /* A disconnected lease expires on its own. */ }
  } };
  update();
  void getDocsFromServer(query(collection(db, "coachPresence"), where("coachUid", "==", uid), limit(50)))
    .then((rows) => Promise.all(rows.docs.filter((row) => row.id !== ref.id &&
      row.data().expiresAt instanceof Timestamp && row.data().expiresAt.toMillis() <= Date.now()).map((row) => deleteDoc(row.ref))))
    .catch(() => { /* Expired records never affect status, even when cleanup fails. */ });
}

export async function stopCoachPresence(): Promise<void> {
  const previous = connection;
  connection = null;
  await previous?.dispose();
}

/** Re-evaluate expiry even when no further snapshot arrives after a crash. */
export function watchCoachPresence(uid: string, render: (status: CoachPresence | null) => void): () => void {
  let records: PresenceLease[] | null = null;
  const update = () => render(records === null ? null : resolveCoachPresence(records, Date.now()));
  const unsubscribe = onSnapshot(query(collection(db, "coachPresence"), where("coachUid", "==", uid)), (snapshot) => {
    const leases = snapshot.docs.flatMap((row): PresenceLease[] => {
      const data = row.data();
      return ["Online", "Busy", "Offline"].includes(data.status) && data.updatedAt instanceof Timestamp && data.expiresAt instanceof Timestamp
        ? [{ status: data.status as CoachPresence, updatedAt: data.updatedAt.toMillis(), expiresAt: data.expiresAt.toMillis() }] : [];
    });
    records = leases.length === 0 && snapshot.metadata.hasPendingWrites ? null : leases;
    update();
  }, () => { records = null; update(); });
  const timer = window.setInterval(update, PRESENCE_CHECK_MS);
  update();
  return () => { unsubscribe(); clearInterval(timer); };
}
