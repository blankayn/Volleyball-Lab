import { collection, doc, getDoc, getDocs, query, serverTimestamp, where, writeBatch, updateDoc, runTransaction, Timestamp } from "firebase/firestore";
import { watchCoachPresence } from "./coach-presence";
import { db } from "./firebase";
import { esc, fullDate } from "./format";
import type { RosterMember } from "./cloud";

export interface CoachingQuestion {
  id: string; coachUid: string; playerUid: string; sessionId: string | null;
  topic: string; text: string; createdAt: number; answer: string | null; answeredAt: number | null;
  followUp1?: CoachingTurn | null; followUp2?: CoachingTurn | null; resolvedAt?: number | null;
  coachReadAt?: number;
}
interface CoachingTurn { text: string; createdAt: number; answer: string | null; answeredAt: number | null }
export const questionPending = (q: CoachingQuestion) => !q.resolvedAt && (q.followUp2 ?? q.followUp1 ?? q).answer === null;
const incomingTimes = (q: CoachingQuestion) => [q.createdAt, q.followUp1?.createdAt ?? 0, q.followUp2?.createdAt ?? 0].filter(at => at > 0);
const unreadCount = (q: CoachingQuestion) => incomingTimes(q).filter(at => at > (q.coachReadAt ?? 0)).length;
const contactText = /https?:\/\/|www[.]|@[a-z0-9_]|[a-z0-9-]+[.][a-z]{2,}\b|[+]?[0-9][0-9 ()-]{6,}[0-9]|instagram|snapchat|telegram|whatsapp|tiktok|discord|facebook/i;
const topics: Record<string, string> = { FirstDrill: "First drill", TrainingFocus: "Training focus", RecordingHelp: "Recording help", Technique: "Technique", DrillInstructions: "Drill instructions", FeedbackClarification: "Feedback clarification" };
const millis = (value: unknown): number => value && typeof value === "object" && "toMillis" in value ? (value as { toMillis(): number }).toMillis() : 0;
export const questionsQuery = (uid: string) => query(collection(db, "coachingQuestions"), where("coachUid", "==", uid));
export async function getCoachQuestions(uid: string): Promise<CoachingQuestion[]> {
  return (await getDocs(questionsQuery(uid))).docs.map((entry) => {
    const data = entry.data();
    return { id: entry.id, coachUid: String(data.coachUid), playerUid: String(data.playerUid), sessionId: data.sessionId ?? null,
      topic: String(data.topic), text: String(data.text), createdAt: millis(data.createdAt), answer: data.answer ?? null,
      answeredAt: data.answeredAt ? millis(data.answeredAt) : null,
      followUp1: data.followUp1 ? { ...data.followUp1, createdAt: millis(data.followUp1.createdAt), answeredAt: millis(data.followUp1.answeredAt) } : null,
      followUp2: data.followUp2 ? { ...data.followUp2, createdAt: millis(data.followUp2.createdAt), answeredAt: millis(data.followUp2.answeredAt) } : null,
      resolvedAt: millis(data.resolvedAt), coachReadAt: millis(data.coachReadAt) };
  }).sort((a, b) => Number(questionPending(b)) - Number(questionPending(a)) || b.createdAt - a.createdAt);
}

let draftOwner = "";
const drafts = new Map<string, string>();
export async function renderCoachQuestions(uid: string, questions: CoachingQuestion[] | null, roster: RosterMember[]): Promise<string> {
  if (draftOwner !== uid) { draftOwner = uid; drafts.clear(); }
  const [reviews, timings] = await Promise.all([
    getDocs(query(collection(db, "coachReviews"), where("coachUid", "==", uid))),
    getDocs(query(collection(db, "coachResponseTimes"), where("coachUid", "==", uid))),
  ]);
  const rates = reviews.docs.map((review) => Number(review.data().rating)).filter((rating) => rating >= 1 && rating <= 5);
  const durations = timings.docs.map((entry) => entry.data()).sort((a, b) => millis(b.answeredAt) - millis(a.answeredAt)).slice(0, 20)
    .map((entry) => millis(entry.answeredAt) - millis(entry.askedAt)).filter((duration) => duration >= 0).sort((a, b) => a - b);
  const middle = Math.floor(durations.length / 2);
  const median = durations.length % 2 ? durations[middle]! : ((durations[middle - 1] ?? 0) + (durations[middle] ?? 0)) / 2;
  const response = durations.length < 3 ? `Building response history (${durations.length}/3 replies)`
    : median < 60_000 ? "Typical response: under a minute" : median < 3_600_000 ? `Typical response: ${Math.floor(median / 60_000)} min`
      : median < 86_400_000 ? `Typical response: ${Math.floor(median / 3_600_000)} hr` : `Typical response: ${Math.floor(median / 86_400_000)} days`;
  return `<section class="hub-page"><div><p class="eyebrow">Training conversations</p><h2>Player questions</h2><p class="muted">Training questions only, with up to two follow-ups. Resolve the current question before starting another.</p></div>
    <div class="hub-summary"><section class="panel"><h3>Your status</h3><p class="hub-presence-status" data-coach-presence role="status">Connecting…</p><p class="muted">Updates automatically with your recent coaching activity.</p></section>
      <section class="panel"><h3>Your coaching profile</h3><p>${esc(response)}</p><p class="muted">Median of up to 20 recent answered questions.</p><p>${rates.length ? `★ ${(rates.reduce((sum, rate) => sum + rate, 0) / rates.length).toFixed(1)} / 5 · ${rates.length} verified session reviews` : "No session reviews yet"}</p></section></div>
    <p id="question-status" role="status" aria-live="polite"></p>
    ${questions === null ? '<div class="form-error" role="alert">Questions could not load. Check your connection and retry this page.</div>' : questions.length === 0 ? '<section class="panel empty"><h3>No questions yet</h3><p>Questions from active players will appear here.</p></section>' : questions.map((question) => {
      const member = roster.find((entry) => entry.playerUid === question.playerUid);
      const active = member?.status === "Active";
      return `<article class="panel hub-question"><div class="hub-question-heading"><div><h3>${esc(member?.playerName || "Player")}</h3><p class="muted">${esc(topics[question.topic] ?? question.topic)} · ${question.sessionId ? "Reviewed session" : "Getting started"} · ${esc(fullDate(question.createdAt))}</p></div><span class="hub-question-state">${unreadCount(question) ? `${unreadCount(question)} unread · ` : ""}${question.resolvedAt ? "Resolved" : questionPending(question) ? "Needs a response" : "Answered"}</span></div>
        ${question.sessionId ? `<a href="#/app/player/${encodeURIComponent(question.playerUid)}/${encodeURIComponent(question.sessionId)}">View the shared session</a>` : ""}
        <p class="hub-question-text">${esc(question.text)}</p>
        ${question.answer !== null ? `<section class="hub-answer"><h4>Your response</h4><p>${esc(question.answer)}</p><small>${esc(fullDate(question.answeredAt ?? 0))}</small></section>` : ""}
        ${[question.followUp1, question.followUp2].filter((turn): turn is CoachingTurn => !!turn).map((turn, index) => `<section class="hub-answer"><h4>Follow-up ${index + 1}</h4><p>${esc(turn.text)}</p>${turn.answer ? `<h4>Your response</h4><p>${esc(turn.answer)}</p>` : ""}</section>`).join("")}
        <span data-question-read="${esc(question.id)}" data-read-at="${Math.max(...incomingTimes(question), 0)}" aria-hidden="true" style="display:block;height:1px"></span>${questionPending(question) && active ? `<form data-question-answer="${esc(question.id)}"><div class="field"><label for="reply-${esc(question.id)}">Training guidance</label><textarea id="reply-${esc(question.id)}" name="reply" maxlength="1000" required rows="3" placeholder="Give a focused next step for this player.">${esc(drafts.get(question.id) ?? "")}</textarea></div><button type="submit" class="btn btn-primary">Send coach response</button><p class="hub-answer-status" role="alert"></p></form>` : !active ? '<p class="muted">This player is no longer on your active roster.</p>' : !question.resolvedAt ? `<button type="button" class="btn" data-resolve-question="${esc(question.id)}">Mark question resolved</button>` : '<p class="muted">Resolved · read only</p>'}</article>`;
    }).join("")}
    <section class="panel"><h3>Verified session reviews</h3>${reviews.empty ? '<p class="muted">Players can leave a review after you have reviewed a submitted session.</p>' : reviews.docs.sort((a, b) => millis(b.data().createdAt) - millis(a.data().createdAt)).map((entry) => `<article class="hub-review"><strong>${"★".repeat(Math.max(1, Math.min(5, Number(entry.data().rating))))} · Verified player</strong><p>${esc(String(entry.data().text || "No written review"))}</p><small>${esc(fullDate(millis(entry.data().createdAt)))}</small></article>`).join("")}</section></section>`;
}

export function wireCoachingHub(root: HTMLElement, uid: string, refresh: () => void): () => void {
  const disposers: (() => void)[] = [];
  const observer = new IntersectionObserver((entries) => {
    if (document.visibilityState !== "visible") return;
    entries.filter(entry => entry.isIntersecting).forEach(entry => {
      const marker = entry.target as HTMLElement;
      const at = Number(marker.dataset.readAt);
      observer.unobserve(marker);
      void runTransaction(db, async transaction => {
        const ref = doc(db, "coachingQuestions", marker.dataset.questionRead!);
        const row = await transaction.get(ref);
        if (row.data()?.coachUid === uid && at > millis(row.data()?.coachReadAt)) transaction.update(ref, { coachReadAt: Timestamp.fromMillis(at) });
      }).catch(() => { if (marker.isConnected) observer.observe(marker); });
    });
  });
  const observeVisibleThreads = () => {
    observer.disconnect();
    if (document.visibilityState === "visible") root.querySelectorAll<HTMLElement>("[data-question-read]").forEach(marker => observer.observe(marker));
  };
  observeVisibleThreads();
  document.addEventListener("visibilitychange", observeVisibleThreads);
  disposers.push(() => { observer.disconnect(); document.removeEventListener("visibilitychange", observeVisibleThreads); });
  root.querySelectorAll<HTMLFormElement>("[data-question-answer]").forEach((form) => {
    const id = form.dataset.questionAnswer!;
    const field = form.querySelector<HTMLTextAreaElement>("textarea")!;
    const button = form.querySelector<HTMLButtonElement>("button")!;
    const status = form.querySelector<HTMLElement>(".hub-answer-status")!;
    const onInput = () => { drafts.set(id, field.value); };
    const onSubmit = async (event: SubmitEvent) => {
      event.preventDefault();
      if (button.disabled || !field.value.trim()) return;
      if (contactText.test(field.value)) { status.textContent = "Training only: no links or contact details."; return; }
      button.disabled = true; field.disabled = true; button.textContent = "Sending…"; status.textContent = "";
      try {
        const ref = doc(db, "coachingQuestions", id);
        const current = await getDoc(ref);
        if (!current.exists() || current.data().coachUid !== uid || current.data().resolvedAt) throw Error("Unavailable question");
        const data = current.data();
        const turn = data.followUp2 ? "followUp2" : data.followUp1 ? "followUp1" : null;
        if ((turn ? data[turn].answer : data.answer) !== null) throw Error("Already answered");
        const batch = writeBatch(db);
        if (turn) batch.update(ref, { [`${turn}.answer`]: field.value.trim().slice(0, 1000), [`${turn}.answeredAt`]: serverTimestamp() });
        else {
          batch.update(ref, { answer: field.value.trim().slice(0, 1000), answeredAt: serverTimestamp() });
          batch.set(doc(db, "coachResponseTimes", id), { coachUid: uid, askedAt: data.createdAt, answeredAt: serverTimestamp() });
        }
        await batch.commit();
        drafts.delete(id); refresh();
      } catch { status.textContent = "Could not send this response. Your text is still here; check your connection and retry."; button.disabled = false; field.disabled = false; button.textContent = "Send coach response"; }
    };
    field.addEventListener("input", onInput); form.addEventListener("submit", onSubmit);
    disposers.push(() => { field.removeEventListener("input", onInput); form.removeEventListener("submit", onSubmit); });
  });
  root.querySelectorAll<HTMLButtonElement>("[data-resolve-question]").forEach((button) => {
    const click = async () => {
      button.disabled = true;
      try { await updateDoc(doc(db, "coachingQuestions", button.dataset.resolveQuestion!), { resolvedAt: serverTimestamp() }); refresh(); }
      catch { button.disabled = false; const status = root.querySelector("#question-status"); if (status) status.textContent = "Could not resolve this question. Please retry."; }
    };
    button.addEventListener("click", click); disposers.push(() => button.removeEventListener("click", click));
  });
  const presence = root.querySelector<HTMLElement>("[data-coach-presence]");
  if (presence) disposers.push(watchCoachPresence(uid, (status) => {
    presence.textContent = status ?? "Status unavailable";
    presence.dataset["presence"] = status ?? "Unknown";
  }));
  return () => { disposers.forEach((dispose) => dispose()); };
}
