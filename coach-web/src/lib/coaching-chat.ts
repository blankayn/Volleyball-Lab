import { SLOW_SAVE, withSlowNotice } from "./pending";
import { collection, doc, getDocs, query, serverTimestamp, setDoc, Timestamp, where } from "firebase/firestore";
import { contactText, markQuestionRead, questionPending, replyToQuestion, topicLabel, type CoachingQuestion } from "./coaching-hub";
import { incomingTimes, unreadCount } from "./coaching-unread";
import { db } from "./firebase";
import { esc, fullDate } from "./format";
import { icon } from "./icons";
import type { RosterMember } from "./cloud";

/* Open chat with a connected player. Earlier question threads stay in the same timeline as
   history. Contact details are refused here and again by firestore.rules. */

export interface ChatMessage { id: string; playerUid: string; fromCoach: boolean; text: string; at: number }
export interface ChatData { messages: ChatMessage[]; readAt: Map<string, number> }
export interface ChatLine { text: string; at: number; fromCoach: boolean; label: string; sessionId: string | null }

const millis = (value: unknown): number => value && typeof value === "object" && "toMillis" in value ? (value as { toMillis(): number }).toMillis() : 0;
export const chatQuery = (uid: string) => query(collection(db, "coachingMessages"), where("coachUid", "==", uid));
const readsQuery = (uid: string) => query(collection(db, "coachingReads"), where("coachUid", "==", uid));

export async function getCoachChat(uid: string): Promise<ChatData> {
  const [messages, reads] = await Promise.all([getDocs(chatQuery(uid)), getDocs(readsQuery(uid))]);
  return {
    messages: messages.docs.map((entry) => {
      const data = entry.data();
      return { id: entry.id, playerUid: String(data.playerUid), fromCoach: data.senderUid === uid, text: String(data.text), at: millis(data.createdAt) };
    }),
    readAt: new Map(reads.docs.map((entry) => [String(entry.data().playerUid), millis(entry.data().coachReadAt)])),
  };
}

export function chatUnread(chat: ChatData | null, playerUid: string): number {
  const read = chat?.readAt.get(playerUid) ?? 0;
  return (chat?.messages ?? []).filter((m) => m.playerUid === playerUid && !m.fromCoach && m.at > read).length;
}

/** Earlier questions and open messages, oldest first. A message still being saved stays last. */
export function chatTimeline(questions: CoachingQuestion[], chat: ChatData | null, playerUid: string): ChatLine[] {
  const lines: ChatLine[] = [];
  for (const q of questions.filter((entry) => entry.playerUid === playerUid)) {
    lines.push({ text: q.text, at: q.createdAt, fromCoach: false, label: topicLabel(q.topic), sessionId: q.sessionId });
    if (q.answer !== null) lines.push({ text: q.answer, at: q.answeredAt ?? 0, fromCoach: true, label: "", sessionId: null });
    for (const turn of [q.followUp1, q.followUp2]) {
      if (!turn) continue;
      lines.push({ text: turn.text, at: turn.createdAt, fromCoach: false, label: "", sessionId: null });
      if (turn.answer) lines.push({ text: turn.answer, at: turn.answeredAt ?? 0, fromCoach: true, label: "", sessionId: null });
    }
  }
  for (const m of chat?.messages ?? []) {
    if (m.playerUid === playerUid) lines.push({ text: m.text, at: m.at, fromCoach: m.fromCoach, label: "", sessionId: null });
  }
  return lines.sort((a, b) => (a.at || Infinity) - (b.at || Infinity));
}

let draftOwner = "";
const drafts = new Map<string, string>();

export function renderChat(uid: string, member: RosterMember, questions: CoachingQuestion[] | null, chat: ChatData | null): string {
  if (draftOwner !== uid) { draftOwner = uid; drafts.clear(); }
  if (questions === null || chat === null) return `<div class="form-error" role="alert">Messages could not load. Check your connection and reload this page.</div>`;
  const name = member.playerName || "Player";
  const lines = chatTimeline(questions, chat, member.playerUid);
  const newest = Math.max(0, ...lines.filter((line) => !line.fromCoach).map((line) => line.at));
  return `<section class="chat" data-chat="${esc(member.playerUid)}" aria-label="Messages with ${esc(name)}">
    <div class="chat-log" data-chat-log tabindex="0" role="log" aria-label="Conversation">
      ${lines.length ? lines.map((line) => `<div class="chat-line${line.fromCoach ? " is-own" : ""}">${line.label ? `<span class="chat-label">${esc(line.label)}${line.sessionId ? ` · <a href="#/app/player/${encodeURIComponent(member.playerUid)}/${encodeURIComponent(line.sessionId)}">View session</a>` : ""}</span>` : ""}<p>${esc(line.text)}</p><small>${line.fromCoach ? "You" : esc(name)} · ${line.at ? esc(fullDate(line.at)) : "Sending…"}</small></div>`).join("") : `<p class="chat-empty">No messages yet. Say hello and share the first thing to work on.</p>`}
      <span data-chat-read="${newest}" aria-hidden="true"></span>
    </div>
    ${member.status === "Active" ? `<form class="chat-form" data-chat-form><label class="visually-hidden" for="chat-${esc(member.playerUid)}">Message to ${esc(name)}</label>
      <textarea id="chat-${esc(member.playerUid)}" maxlength="1000" rows="2" required placeholder="Write to ${esc(name)}">${esc(drafts.get(member.playerUid) ?? "")}</textarea>
      <button type="submit" class="btn btn-primary">${icon("message")} Send</button></form>
      <p class="chat-hint" data-chat-status role="alert">Links, phone numbers, emails and social accounts can't be shared here.</p>`
      : `<p class="chat-hint">This player is no longer on your active roster, so the chat is read only.</p>`}
  </section>`;
}

export async function sendChat(uid: string, playerUid: string, text: string, questions: CoachingQuestion[]): Promise<void> {
  // Answering a waiting question keeps older app versions in step; everything else is open chat.
  const waiting = questions.filter((q) => q.playerUid === playerUid && questionPending(q)).sort((a, b) => a.createdAt - b.createdAt)[0];
  if (waiting) { await replyToQuestion(uid, waiting.id, text); return; }
  const ref = doc(collection(db, "coachingMessages"));
  await setDoc(ref, { id: ref.id, coachUid: uid, playerUid, senderUid: uid, text, createdAt: serverTimestamp() });
}

export function wireChat(root: HTMLElement, uid: string, questions: CoachingQuestion[] | null, chat: ChatData | null, refresh: () => unknown): () => void {
  const section = root.querySelector<HTMLElement>("[data-chat]");
  if (!section || questions === null || chat === null) return () => {};
  const playerUid = section.dataset.chat!;
  const log = section.querySelector<HTMLElement>("[data-chat-log]")!;
  const marker = section.querySelector<HTMLElement>("[data-chat-read]")!;
  const disposers: (() => void)[] = [];
  const toEnd = () => { log.scrollTop = log.scrollHeight; };
  const details = section.closest("details");
  toEnd();
  details?.addEventListener("toggle", toEnd);
  disposers.push(() => details?.removeEventListener("toggle", toEnd));

  let marking = false;
  const markRead = async () => {
    const own = questions.filter((q) => q.playerUid === playerUid);
    const at = Math.max(0, ...chat.messages.filter((m) => m.playerUid === playerUid && !m.fromCoach).map((m) => m.at));
    if (marking) return;
    marking = true;
    try {
      await Promise.all(own.filter((q) => unreadCount(q) > 0).map((q) => markQuestionRead(uid, q.id, Math.max(...incomingTimes(q)))));
      if (at > (chat.readAt.get(playerUid) ?? 0)) {
        await setDoc(doc(db, "coachingReads", `${uid}_${playerUid}`), { coachUid: uid, playerUid, coachReadAt: Timestamp.fromMillis(at) }, { merge: true });
        chat.readAt.set(playerUid, at);
      }
    } catch { marking = false; }
  };
  const observer = new IntersectionObserver((entries) => {
    if (document.visibilityState === "visible" && entries.some((entry) => entry.isIntersecting)) void markRead();
  });
  const watch = () => { observer.disconnect(); if (document.visibilityState === "visible") observer.observe(marker); };
  watch();
  document.addEventListener("visibilitychange", watch);
  disposers.push(() => { observer.disconnect(); document.removeEventListener("visibilitychange", watch); });

  const form = section.querySelector<HTMLFormElement>("[data-chat-form]");
  if (form) {
    const field = form.querySelector<HTMLTextAreaElement>("textarea")!;
    const button = form.querySelector<HTMLButtonElement>("button")!;
    const status = section.querySelector<HTMLElement>("[data-chat-status]")!;
    const hint = status.textContent ?? "";
    const say = (message: string, error: boolean) => { status.textContent = message; status.classList.toggle("is-error", error); };
    const onInput = () => {
      drafts.set(playerUid, field.value);
      if (contactText.test(field.value)) say("Remove links, phone numbers, emails or social accounts to send.", true);
      else say(hint, false);
    };
    const onSubmit = async (event: SubmitEvent) => {
      event.preventDefault();
      const text = field.value.trim();
      if (button.disabled || !text) return;
      if (contactText.test(text)) { say("Remove links, phone numbers, emails or social accounts to send.", true); return; }
      button.disabled = true; field.disabled = true;
      try {
        await withSlowNotice(sendChat(uid, playerUid, text.slice(0, 1000), questions), () => say(SLOW_SAVE, true));
        drafts.delete(playerUid);
        await refresh();
        // Normally the refresh replaced this form; if it could not, it must not stay locked.
        if (form.isConnected) { field.value = ""; say(hint, false); button.disabled = false; field.disabled = false; }
      } catch {
        say("Could not send. Your text is still here; check your connection and try again.", true);
        button.disabled = false; field.disabled = false;
      }
    };
    field.addEventListener("input", onInput);
    form.addEventListener("submit", onSubmit);
    disposers.push(() => { field.removeEventListener("input", onInput); form.removeEventListener("submit", onSubmit); });
  }
  return () => { disposers.forEach((dispose) => dispose()); };
}
