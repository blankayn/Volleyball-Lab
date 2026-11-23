import { collection, doc, getDoc, getDocs, query, serverTimestamp, where, writeBatch, runTransaction, Timestamp } from "firebase/firestore";
import { db } from "./firebase";

export interface CoachingQuestion {
  id: string; coachUid: string; playerUid: string; sessionId: string | null;
  topic: string; text: string; createdAt: number; answer: string | null; answeredAt: number | null;
  followUp1?: CoachingTurn | null; followUp2?: CoachingTurn | null; resolvedAt?: number | null;
  coachReadAt?: number;
}
interface CoachingTurn { text: string; createdAt: number; answer: string | null; answeredAt: number | null }
export const questionPending = (q: CoachingQuestion) => !q.resolvedAt && (q.followUp2 ?? q.followUp1 ?? q).answer === null;
export const contactText = /https?:\/\/|www[.]|@[a-z0-9_]|[a-z0-9-]+[.][a-z]{2,}\b|[+]?[0-9][0-9 ()-]{6,}[0-9]|instagram|snapchat|telegram|whatsapp|tiktok|discord|facebook/i;
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

export const topicLabel = (topic: string): string => topics[topic] ?? topic;

/** Answers a waiting question or follow-up, with the response-time receipt the rules require. */
export async function replyToQuestion(uid: string, id: string, text: string): Promise<void> {
  const ref = doc(db, "coachingQuestions", id);
  const current = await getDoc(ref);
  if (!current.exists() || current.data().coachUid !== uid || current.data().resolvedAt) throw Error("Unavailable question");
  const data = current.data();
  const turn = data.followUp2 ? "followUp2" : data.followUp1 ? "followUp1" : null;
  if ((turn ? data[turn].answer : data.answer) !== null) throw Error("Already answered");
  const batch = writeBatch(db);
  if (turn) batch.update(ref, { [`${turn}.answer`]: text.slice(0, 1000), [`${turn}.answeredAt`]: serverTimestamp() });
  else {
    batch.update(ref, { answer: text.slice(0, 1000), answeredAt: serverTimestamp() });
    batch.set(doc(db, "coachResponseTimes", id), { coachUid: uid, askedAt: data.createdAt, answeredAt: serverTimestamp() });
  }
  await batch.commit();
}

export async function markQuestionRead(uid: string, id: string, at: number): Promise<void> {
  await runTransaction(db, async transaction => {
    const ref = doc(db, "coachingQuestions", id);
    const row = await transaction.get(ref);
    if (row.data()?.coachUid === uid && at > millis(row.data()?.coachReadAt)) transaction.update(ref, { coachReadAt: Timestamp.fromMillis(at) });
  });
}
