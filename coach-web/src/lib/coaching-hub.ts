import { collection, doc, getDoc, getDocs, query, serverTimestamp, setDoc, where, writeBatch } from "firebase/firestore";
import { db } from "./firebase";
import { esc, fullDate } from "./format";
import type { RosterMember } from "./cloud";

export interface CoachingQuestion {
  id: string; coachUid: string; playerUid: string; sessionId: string | null;
  topic: string; text: string; createdAt: number; answer: string | null; answeredAt: number | null;
}
const topics: Record<string, string> = { FirstDrill: "First drill", TrainingFocus: "Training focus", RecordingHelp: "Recording help", Technique: "Technique", DrillInstructions: "Drill instructions", FeedbackClarification: "Feedback clarification" };
export const availabilityLabels = { Available: "Available", Busy: "Busy", Offline: "Offline", FullyBooked: "Fully booked" } as const;
const millis = (value: unknown): number => value && typeof value === "object" && "toMillis" in value ? (value as { toMillis(): number }).toMillis() : 0;
export const questionsQuery = (uid: string) => query(collection(db, "coachingQuestions"), where("coachUid", "==", uid));
export async function getCoachQuestions(uid: string): Promise<CoachingQuestion[]> {
  return (await getDocs(questionsQuery(uid))).docs.map((entry) => {
    const data = entry.data();
    return { id: entry.id, coachUid: String(data.coachUid), playerUid: String(data.playerUid), sessionId: data.sessionId ?? null,
      topic: String(data.topic), text: String(data.text), createdAt: millis(data.createdAt), answer: data.answer ?? null,
      answeredAt: data.answeredAt ? millis(data.answeredAt) : null };
  }).sort((a, b) => Number(a.answer !== null) - Number(b.answer !== null) || a.createdAt - b.createdAt);
}

let draftOwner = "";
const drafts = new Map<string, string>();
export async function renderCoachQuestions(uid: string, questions: CoachingQuestion[] | null, roster: RosterMember[]): Promise<string> {
  if (draftOwner !== uid) { draftOwner = uid; drafts.clear(); }
  const [availability, reviews, timings] = await Promise.all([
    getDoc(doc(db, "coachAvailability", uid)),
    getDocs(query(collection(db, "coachReviews"), where("coachUid", "==", uid))),
    getDocs(query(collection(db, "coachResponseTimes"), where("coachUid", "==", uid))),
  ]);
  const status = availability.data()?.status;
  const rates = reviews.docs.map((review) => Number(review.data().rating)).filter((rating) => rating >= 1 && rating <= 5);
  const durations = timings.docs.map((entry) => entry.data()).sort((a, b) => millis(b.answeredAt) - millis(a.answeredAt)).slice(0, 20)
    .map((entry) => millis(entry.answeredAt) - millis(entry.askedAt)).filter((duration) => duration >= 0).sort((a, b) => a - b);
  const middle = Math.floor(durations.length / 2);
  const median = durations.length % 2 ? durations[middle]! : ((durations[middle - 1] ?? 0) + (durations[middle] ?? 0)) / 2;
  const response = durations.length < 3 ? `Building response history (${durations.length}/3 replies)`
    : median < 60_000 ? "Typical response: under a minute" : median < 3_600_000 ? `Typical response: ${Math.floor(median / 60_000)} min`
      : median < 86_400_000 ? `Typical response: ${Math.floor(median / 3_600_000)} hr` : `Typical response: ${Math.floor(median / 86_400_000)} days`;
  return `<section class="hub-page"><div><p class="eyebrow">Training conversations</p><h2>Player questions</h2><p class="muted">One question and one response per reviewed session, plus a getting-started question for newly accepted players.</p></div>
    <div class="hub-summary"><section class="panel"><h3>Your availability</h3><p class="muted">Shown on your coach profile. Update this when your capacity changes.</p>
      <label class="field" for="coach-availability">Availability<select id="coach-availability"><option value="" disabled${!status ? " selected" : ""}>Choose availability</option>${Object.entries(availabilityLabels).map(([value, label]) => `<option value="${value}"${status === value ? " selected" : ""}>${label}</option>`).join("")}</select></label>
      <button type="button" class="btn btn-primary" id="save-availability">Save availability</button><p id="availability-status" role="status"></p></section>
      <section class="panel"><h3>Your coaching profile</h3><p>${esc(response)}</p><p class="muted">Median of up to 20 recent answered questions.</p><p>${rates.length ? `★ ${(rates.reduce((sum, rate) => sum + rate, 0) / rates.length).toFixed(1)} / 5 · ${rates.length} verified session reviews` : "No session reviews yet"}</p></section></div>
    <p id="question-status" role="status" aria-live="polite"></p>
    ${questions === null ? '<div class="form-error" role="alert">Questions could not load. Check your connection and retry this page.</div>' : questions.length === 0 ? '<section class="panel empty"><h3>No questions yet</h3><p>Questions from active players will appear here.</p></section>' : questions.map((question) => {
      const member = roster.find((entry) => entry.playerUid === question.playerUid);
      const active = member?.status === "Active";
      return `<article class="panel hub-question"><div class="hub-question-heading"><div><h3>${esc(member?.playerName || "Player")}</h3><p class="muted">${esc(topics[question.topic] ?? question.topic)} · ${question.sessionId ? "Reviewed session" : "Getting started"} · ${esc(fullDate(question.createdAt))}</p></div><span class="hub-question-state">${question.answer === null ? "Needs a response" : "Answered"}</span></div>
        ${question.sessionId ? `<a href="#/app/player/${encodeURIComponent(question.playerUid)}/${encodeURIComponent(question.sessionId)}">View the shared session</a>` : ""}
        <p class="hub-question-text">${esc(question.text)}</p>
        ${question.answer !== null ? `<section class="hub-answer"><h4>Your response</h4><p>${esc(question.answer)}</p><small>${esc(fullDate(question.answeredAt ?? 0))}</small></section>` : active ? `<form data-question-answer="${esc(question.id)}"><div class="field"><label for="reply-${esc(question.id)}">Training guidance</label><textarea id="reply-${esc(question.id)}" name="reply" maxlength="1000" required rows="3" placeholder="Give a focused next step for this player.">${esc(drafts.get(question.id) ?? "")}</textarea></div><button type="submit" class="btn btn-primary">Send coach response</button><p class="hub-answer-status" role="alert"></p></form>` : '<p class="muted">This player is no longer on your active roster.</p>'}</article>`;
    }).join("")}
    <section class="panel"><h3>Verified session reviews</h3>${reviews.empty ? '<p class="muted">Players can leave a review after you have reviewed a submitted session.</p>' : reviews.docs.sort((a, b) => millis(b.data().createdAt) - millis(a.data().createdAt)).map((entry) => `<article class="hub-review"><strong>${"★".repeat(Math.max(1, Math.min(5, Number(entry.data().rating))))} · Verified player</strong><p>${esc(String(entry.data().text || "No written review"))}</p><small>${esc(fullDate(millis(entry.data().createdAt)))}</small></article>`).join("")}</section></section>`;
}

export function wireCoachingHub(root: HTMLElement, uid: string, refresh: () => void): () => void {
  const disposers: (() => void)[] = [];
  root.querySelectorAll<HTMLFormElement>("[data-question-answer]").forEach((form) => {
    const id = form.dataset.questionAnswer!;
    const field = form.querySelector<HTMLTextAreaElement>("textarea")!;
    const button = form.querySelector<HTMLButtonElement>("button")!;
    const status = form.querySelector<HTMLElement>(".hub-answer-status")!;
    const onInput = () => { drafts.set(id, field.value); };
    const onSubmit = async (event: SubmitEvent) => {
      event.preventDefault();
      if (button.disabled || !field.value.trim()) return;
      if (/(https?:\/\/|www[.])/i.test(field.value)) { status.textContent = "Keep guidance text only; links are not supported."; return; }
      button.disabled = true; field.disabled = true; button.textContent = "Sending…"; status.textContent = "";
      try {
        const ref = doc(db, "coachingQuestions", id);
        const current = await getDoc(ref);
        if (!current.exists() || current.data().coachUid !== uid || current.data().answer !== null) throw Error("Unavailable question");
        const batch = writeBatch(db);
        batch.update(ref, { answer: field.value.trim().slice(0, 1000), answeredAt: serverTimestamp() });
        batch.set(doc(db, "coachResponseTimes", id), { coachUid: uid, askedAt: current.data().createdAt, answeredAt: serverTimestamp() });
        await batch.commit();
        drafts.delete(id); refresh();
      } catch { status.textContent = "Could not send this response. Your text is still here; check your connection and retry."; button.disabled = false; field.disabled = false; button.textContent = "Send coach response"; }
    };
    field.addEventListener("input", onInput); form.addEventListener("submit", onSubmit);
    disposers.push(() => { field.removeEventListener("input", onInput); form.removeEventListener("submit", onSubmit); });
  });
  const save = root.querySelector<HTMLButtonElement>("#save-availability");
  const onSave = async () => {
    const status = root.querySelector<HTMLElement>("#availability-status")!;
    const field = root.querySelector<HTMLSelectElement>("#coach-availability")!;
    if (!save || save.disabled || !(field.value in availabilityLabels)) return;
    save.disabled = true; status.textContent = "Saving…";
    try { await setDoc(doc(db, "coachAvailability", uid), { coachUid: uid, status: field.value, updatedAt: serverTimestamp() }); status.textContent = "Availability saved."; }
    catch { status.textContent = "Could not save availability. Please try again."; }
    finally { save.disabled = false; }
  };
  save?.addEventListener("click", onSave);
  return () => { disposers.forEach((dispose) => dispose()); save?.removeEventListener("click", onSave); };
}
