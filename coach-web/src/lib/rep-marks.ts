/* A verified coach's Right / Wrong mark on one rep. The player's own app reads it, lets it
   outrank the camera, and adapts its coaching to it. See docs/adaptive-coaching.md. */

import { collection, deleteDoc, doc, getDoc, getDocs, query, serverTimestamp, setDoc, where } from "firebase/firestore";
import { db } from "./firebase";
import { coachChecks, coachStatus, loadTrustView } from "./coach-trust";
import { getProfile, type Skill } from "./cloud";
import { cuesFor } from "./cues";
import { esc } from "./format";
import { SLOW_SAVE, withSlowNotice } from "./pending";
import { loadPlan } from "./plan";

export type Verdict = "Right" | "Wrong";
export interface Mark { verdict: Verdict; cueIds: string[] }
export interface MarkContext {
  sessionId: string; playerUid: string; coachUid: string; skill: Skill; repCount: number;
  state: "ready" | "unverified" | "free";
  marks: Map<number, Mark>;
}

export const markId = (sessionId: string, repNumber: number, coachUid: string): string => `${sessionId}_${repNumber}_${coachUid}`;

/** Jump height is an estimate, not a pose check, so it is never something to mark. */
export const markableCues = (skill: string) => cuesFor(skill).filter((c) => c.id !== "SpikeJumpHeight");

export async function loadMarkContext(sessionId: string, coachUid: string): Promise<MarkContext | null> {
  const session = (await getDoc(doc(db, "sessions", sessionId))).data();
  if (!session) return null;
  const context: MarkContext = {
    sessionId, coachUid, playerUid: String(session["ownerUid"] ?? ""), skill: session["skill"] as Skill,
    repCount: Number(session["repCount"] ?? 0), state: "unverified", marks: new Map(),
  };
  if (!(await loadPlan(coachUid))) return { ...context, state: "free" };
  const [view, profile] = await Promise.all([loadTrustView(coachUid), getProfile(coachUid)]);
  if (coachStatus(coachChecks(view, profile.name, profile.organisation), view.verifiedByTeam) === "none") return context;
  const saved = await getDocs(query(collection(db, "repLabels"), where("coachUid", "==", coachUid), where("sessionId", "==", sessionId)));
  saved.docs.forEach((d) => {
    const data = d.data();
    if (data["verdict"] === "Right" || data["verdict"] === "Wrong")
      context.marks.set(Number(data["repNumber"]), { verdict: data["verdict"], cueIds: Array.isArray(data["cueIds"]) ? data["cueIds"].map(String) : [] });
  });
  return { ...context, state: "ready" };
}

export async function saveMark(context: MarkContext, repNumber: number, mark: Mark | null): Promise<void> {
  const ref = doc(db, "repLabels", markId(context.sessionId, repNumber, context.coachUid));
  if (!mark) { await deleteDoc(ref); context.marks.delete(repNumber); return; }
  const known = new Set(markableCues(context.skill).map((c) => c.id));
  const cueIds = mark.verdict === "Wrong" ? mark.cueIds.filter((id) => known.has(id)) : [];
  await setDoc(ref, { sessionId: context.sessionId, playerUid: context.playerUid, coachUid: context.coachUid,
    skill: context.skill, repNumber, verdict: mark.verdict, cueIds, createdAt: serverTimestamp() });
  context.marks.set(repNumber, { verdict: mark.verdict, cueIds });
}

export function markHtml(context: MarkContext, repNumber: number): string {
  if (context.state === "free") return `<p class="muted rep-mark-note">Marking reps Right or Wrong is part of Pro. <a href="#/app/account">See plans</a></p>`;
  if (context.state === "unverified") return `<p class="muted rep-mark-note">Get verified in <a href="#/app/account">Account</a> to mark reps Right or Wrong.</p>`;
  if (repNumber < 1 || repNumber > context.repCount) return "";
  const mark = context.marks.get(repNumber);
  const verdict = (value: Verdict) => `<button type="button" class="rep-mark-button is-${value.toLowerCase()}" data-mark="${value}" aria-pressed="${mark?.verdict === value}">${value}</button>`;
  const wrong = mark?.verdict === "Wrong" ? `<fieldset class="rep-mark-cues"><legend>What was wrong?</legend>${markableCues(context.skill)
    .map((c) => `<button type="button" data-mark-cue="${esc(c.id)}" aria-pressed="${mark.cueIds.includes(c.id)}">${esc(c.label)}</button>`).join("")}</fieldset>` : "";
  return `<div class="rep-mark-row"><strong>In your own judgement, was this rep right?</strong><span>${verdict("Right")}${verdict("Wrong")}</span></div>${wrong}
    <p class="muted rep-mark-note" data-mark-status role="status">${mark ? "Saved. Press it again to undo." : "The player's AI coach learns from your answer."}</p>`;
}

/** Fills the mark area of whichever rep is open. rep-notes.ts says which with "reppick". */
export function attachRepMarks(host: HTMLElement, sessionId: string, coachUid: string): () => void {
  let context: MarkContext | null = null, slot: HTMLElement | null = null, repNumber = 0, gone = false, saving = false;
  const draw = (focus = "") => {
    if (!slot || !context) return;
    slot.innerHTML = markHtml(context, repNumber);
    if (focus) slot.querySelector<HTMLElement>(focus)?.focus();
  };
  const onPick = (event: Event) => {
    const picked = (event as CustomEvent<{ repNumber: number; slot: HTMLElement }>).detail;
    slot = picked.slot; repNumber = picked.repNumber;
    draw();
  };
  const onClick = async (event: Event) => {
    const button = (event.target as Element).closest<HTMLButtonElement>("[data-mark], [data-mark-cue]");
    if (!button || !context || saving || context.state !== "ready") return;
    const current = context.marks.get(repNumber), cue = button.dataset["markCue"], rep = repNumber;
    let next: Mark | null;
    if (cue) {
      if (!current) return;
      next = { verdict: "Wrong", cueIds: current.cueIds.includes(cue) ? current.cueIds.filter((id) => id !== cue) : [...current.cueIds, cue] };
    } else {
      const verdict = button.dataset["mark"] as Verdict;
      next = current?.verdict === verdict ? null : { verdict, cueIds: [] };
    }
    const here = slot, status = () => here?.querySelector<HTMLElement>("[data-mark-status]");
    saving = true;
    here?.querySelectorAll("button").forEach((b) => { b.disabled = true; });
    if (status()) status()!.textContent = "Saving…";
    let failed = false;
    try { await withSlowNotice(saveMark(context, rep, next), () => { if (status()) status()!.textContent = SLOW_SAVE; }); }
    catch { failed = true; }
    saving = false;
    if (gone) return;
    draw(rep === repNumber ? (cue ? `[data-mark-cue="${cue}"]` : `[data-mark="${button.dataset["mark"]}"]`) : "");
    if (failed && rep === repNumber && status()) status()!.textContent = "That did not save. The player may have disconnected. Try again.";
  };
  host.addEventListener("reppick", onPick);
  host.addEventListener("click", onClick);
  void loadMarkContext(sessionId, coachUid).then((loaded) => { if (!gone) { context = loaded; draw(); } }).catch(() => undefined);
  return () => { gone = true; host.removeEventListener("reppick", onPick); host.removeEventListener("click", onClick); };
}
