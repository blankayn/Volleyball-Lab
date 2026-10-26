import { randomUUID } from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { defineSecret, defineString } from "firebase-functions/params";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { deadlineIsDue, emailDeliveryId, emailMessage, sendResend, submissionEmailCategory } from "./coach-email-policy.mjs";

const resendKey = defineSecret("RESEND_API_KEY");
const sender = defineString("COACH_EMAIL_FROM", { description: "Verified Resend sender, e.g. Volleyball Lab <alerts@example.com>" });
const portalUrl = defineString("COACH_PORTAL_URL", { description: "HTTPS address of the deployed coaching website" });
const region = "asia-southeast1";

async function deliver(coachUid, playerUid, eventKey, category, details) {
  const db = getFirestore();
  const [settingsDoc, profileDoc, deletion, playerDeletion, member, account] = await Promise.all([
    db.doc(`coaches/${coachUid}/webSettings/preferences`).get(), db.doc(`coaches/${coachUid}`).get(),
    db.doc(`accountDeletions/${coachUid}`).get(), db.doc(`accountDeletions/${playerUid}`).get(),
    db.doc(`roster/${coachUid}/members/${playerUid}`).get(), db.doc(`users/${coachUid}`).get(),
  ]);
  const settings = settingsDoc.data();
  if (deletion.exists || playerDeletion.exists || account.data()?.role !== "COACH" || member.data()?.status !== "Active" || settings?.emailNotifications !== true) return;
  if (category === "deadline" ? settings.deadlineReminders === false : category === "submission" ? settings.submissionAlerts === false || profileDoc.data()?.notifySubmissions === false : settings.trainingNotifications === false) return;
  let user;
  try { user = await getAuth().getUser(coachUid); } catch (error) { if (error.code === "auth/user-not-found") return; throw error; }
  if (!user.email || !user.emailVerified || user.disabled) return;
  const id = emailDeliveryId(coachUid, eventKey);
  const receipt = db.doc(`coachEmailDeliveries/${id}`);
  const attempt = randomUUID();
  const now = Date.now();
  const claimed = await db.runTransaction(async tx => {
    const current = (await tx.get(receipt)).data();
    if (current?.state === "sent" || current?.state === "expired") return false;
    // Resend retains idempotency keys for 24h. Do not retry an uncertain delivery beyond that window.
    if (current?.firstAttemptAt && now - current.firstAttemptAt > 23 * 3_600_000) { tx.set(receipt, { state: "expired", updatedAt: now }, { merge: true }); return false; }
    if (current?.leaseUntil > now) throw new Error("email_delivery_in_progress");
    tx.set(receipt, { coachUid, state: "sending", attempt, firstAttemptAt: current?.firstAttemptAt ?? now, leaseUntil: now + 120_000, updatedAt: now });
    return true;
  });
  if (!claimed) return;
  try {
    const message = emailMessage({ category, playerName: member.data()?.playerName || "A player", ...details, portalUrl: portalUrl.value() });
    const providerId = await sendResend({ apiKey: resendKey.value(), from: sender.value(), to: user.email, id, message });
    await receipt.set({ state: "sent", providerId, leaseUntil: 0, updatedAt: Date.now() }, { merge: true });
  } catch (error) {
    await db.runTransaction(async tx => {
      const current = (await tx.get(receipt)).data();
      if (current?.attempt === attempt && current.state !== "sent") tx.update(receipt, { leaseUntil: 0, state: "retry", updatedAt: Date.now() });
    });
    throw error;
  }
}

export const emailCoachOnSubmission = onDocumentWritten({ document: "submissions/{submissionId}", region, secrets: [resendKey], retry: true, maxInstances: 2, concurrency: 1 }, async event => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!after || after.reviewedAt !== null || before?.submittedAt === after.submittedAt) return;
  const db = getFirestore();
  const [settings, profile] = await Promise.all([db.doc(`coaches/${after.coachUid}/webSettings/preferences`).get(), db.doc(`coaches/${after.coachUid}`).get()]);
  const category = submissionEmailCategory(settings.data(), profile.data());
  if (!category) return;
  await deliver(after.coachUid, after.playerUid, `submission/${event.params.submissionId}/${after.submittedAt}`, category, {});
});

export const emailCoachDeadlineReminders = onSchedule({ schedule: "every 60 minutes", region, timeZone: "Asia/Manila", secrets: [resendKey], timeoutSeconds: 300, maxInstances: 1, retryCount: 3 }, async () => {
  const db = getFirestore();
  const now = Date.now();
  let cursor;
  let processed = 0;
  do {
    let query = db.collection("assignments").where("dueAt", ">", now).where("dueAt", "<=", now + 86_400_000).orderBy("dueAt").limit(100);
    if (cursor) query = query.startAfter(cursor);
    const page = await query.get();
    if (page.empty) return;
    let failed = false;
    for (const item of page.docs) {
      const assignment = item.data();
      if (!deadlineIsDue(assignment, now)) continue;
      try {
        await deliver(assignment.coachUid, assignment.playerUid, `deadline/${item.id}/${assignment.dueAt}`, "deadline", { assignmentTitle: assignment.title, dueAt: assignment.dueAt });
      } catch { failed = true; }
      // Keep the batch below Resend's default request rate.
      await new Promise(resolve => setTimeout(resolve, 600));
    }
    if (failed) throw new Error("deadline_email_retry_required");
    processed += page.size;
    cursor = page.docs.at(-1);
    if (page.size < 100) return;
  } while (processed < 300);
});
