import { randomUUID } from "node:crypto";
import { getFirestore } from "firebase-admin/firestore";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onDocumentDeleted } from "firebase-functions/v2/firestore";
import { DEFAULT_NVIDIA_MODEL, EXPLANATION_VERSION, explanationHash, requestNvidiaExplanation, sessionEvidence } from "./session-explanation-policy.mjs";

const nvidiaKey = defineSecret("NVIDIA_API_KEY");
const model = defineString("NVIDIA_SESSION_MODEL", { default: DEFAULT_NVIDIA_MODEL });

export function createSessionExplainer({ db, apiKey, modelName, generate = requestNvidiaExplanation, now = Date.now }) {
  return async request => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in to read this session.");
    const sessionId = request.data?.sessionId;
    if (typeof sessionId !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(sessionId) || typeof request.data?.generate !== "boolean" || Object.keys(request.data).some(key => !["sessionId", "generate"].includes(key))) throw new HttpsError("invalid-argument", "Choose a saved session.");
    const sessionRef = db.doc(`sessions/${sessionId}`);
    const [sessionDoc, deleting] = await Promise.all([sessionRef.get(), db.doc(`accountDeletions/${uid}`).get()]);
    if (deleting.exists || !sessionDoc.exists) throw new HttpsError("not-found", "This session is unavailable.");
    const session = sessionDoc.data();
    if (typeof session.ownerUid !== "string") throw new HttpsError("failed-precondition", "This session is incomplete.");
    if (uid !== session.ownerUid) {
      const [member, ownerDeletion, account] = await Promise.all([db.doc(`roster/${uid}/members/${session.ownerUid}`).get(), db.doc(`accountDeletions/${session.ownerUid}`).get(), db.doc(`users/${uid}`).get()]);
      if (!session.sharedWith?.includes(uid) || member.data()?.status !== "Active" || ownerDeletion.exists || account.data()?.role !== "COACH") throw new HttpsError("permission-denied", "You cannot access this session.");
    }
    let evidence;
    try { evidence = sessionEvidence(session); } catch { throw new HttpsError("failed-precondition", "This session has no supported measurements."); }
    const selectedModel = modelName();
    const hash = explanationHash(evidence, selectedModel);
    const cacheRef = sessionRef.collection("aiExplanations").doc(EXPLANATION_VERSION);
    // Include source/access/deletion reads in each write transaction so a
    // concurrent deletion cannot recreate a cache or usage record after cleanup.
    const guardWrite = async tx => {
      const [latest, callerDeletion, ownerDeletion] = await Promise.all([
        tx.get(sessionRef), tx.get(db.doc(`accountDeletions/${uid}`)), tx.get(db.doc(`accountDeletions/${session.ownerUid}`)),
      ]);
      if (!latest.exists || callerDeletion.exists || ownerDeletion.exists) throw new HttpsError("permission-denied", "This session is unavailable.");
      if (latest.data().ownerUid !== session.ownerUid || explanationHash(sessionEvidence(latest.data()), selectedModel) !== hash) throw new HttpsError("aborted", "This session changed. Generate its updated explanation.");
      if (uid !== session.ownerUid) {
        const [connection, account] = await Promise.all([tx.get(db.doc(`roster/${uid}/members/${session.ownerUid}`)), tx.get(db.doc(`users/${uid}`))]);
        if (!latest.data().sharedWith?.includes(uid) || connection.data()?.status !== "Active" || account.data()?.role !== "COACH") throw new HttpsError("permission-denied", "Your session access changed.");
      }
    };
    const cached = (await cacheRef.get()).data();
    if (cached?.fingerprint === hash && cached.status === "ready") return { status: "ready", evidence, explanation: cached.explanation, generatedAt: cached.generatedAt, cached: true };
    if (!evidence.strengths.length && !evidence.priorities.length) return { status: "insufficient", evidence };
    if (!request.data.generate) return { status: "empty", evidence };
    const lease = randomUUID();
    const usageRef = db.doc(`aiUsage/${uid}`);
    const time = now();
    const day = new Date(time).toISOString().slice(0, 10);
    const racedCache = await db.runTransaction(async tx => {
      await guardWrite(tx);
      const [currentDoc, usageDoc] = await Promise.all([tx.get(cacheRef), tx.get(usageRef)]);
      const current = currentDoc.data();
      const usage = usageDoc.data();
      if (current?.status === "ready" && current.fingerprint === hash) return current;
      if (current?.leaseUntil > time) throw new HttpsError("aborted", "An explanation is being prepared. Try again shortly.");
      if (usage?.day === day && usage.count >= 20) throw new HttpsError("resource-exhausted", "Your daily explanation limit has been reached.");
      if (usage?.lastRequestedAt > time - 15_000) throw new HttpsError("resource-exhausted", "Wait a moment before generating another explanation.");
      tx.set(cacheRef, { status: "generating", lease, fingerprint: hash, leaseUntil: time + 65_000 });
      tx.set(usageRef, { day, count: usage?.day === day ? usage.count + 1 : 1, lastRequestedAt: time });
      return null;
    });
    if (racedCache) return { status: "ready", evidence, explanation: racedCache.explanation, generatedAt: racedCache.generatedAt, cached: true };
    try {
      const explanation = await generate({ apiKey: apiKey(), model: selectedModel, evidence });
      const generatedAt = now();
      // Atomically recheck source and access after the external call.
      await db.runTransaction(async tx => {
        await guardWrite(tx);
        if ((await tx.get(cacheRef)).data()?.lease !== lease) throw new HttpsError("aborted", "Generate the explanation again.");
        tx.set(cacheRef, { status: "ready", fingerprint: hash, explanation, generatedAt, model: selectedModel, promptVersion: EXPLANATION_VERSION, leaseUntil: 0 });
      });
      return { status: "ready", evidence, explanation, generatedAt, cached: false };
    } catch (error) {
      await db.runTransaction(async tx => { if ((await tx.get(cacheRef)).data()?.lease === lease) tx.delete(cacheRef); });
      if (error instanceof HttpsError) throw error;
      throw new HttpsError(error.message === "nvidia_rate_limited" ? "resource-exhausted" : "unavailable", "AI explanation is unavailable. Your measured feedback is still available.");
    }
  };
}

export const explainTrainingSession = onCall({ region: "asia-southeast1", timeoutSeconds: 60, memory: "256MiB", maxInstances: 2, concurrency: 8, secrets: [nvidiaKey] }, request => createSessionExplainer({ db: getFirestore(), apiKey: () => nvidiaKey.value(), modelName: () => model.value() })(request));

export const removeSessionExplanation = onDocumentDeleted({ document: "sessions/{sessionId}", region: "asia-southeast1", retry: true, maxInstances: 2 }, async event => {
  await getFirestore().recursiveDelete(getFirestore().collection(`sessions/${event.params.sessionId}/aiExplanations`));
});
