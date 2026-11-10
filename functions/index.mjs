import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import { onRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { paidCheckout, verifySignature } from "./billing-policy.mjs";
import { BillingError, fulfillGcashPayment, startGcashCheckout } from "./billing-checkout.mjs";
import { adminBillingStore } from "./admin-billing-store.mjs";
export { deleteAccount, resumeAccountDeletion } from './account-deletion.mjs';
export { notifyPlayerOnCoachNote, notifyPlayerOnAssignment, notifyCoachOnSubmission, notifyPlayerOnCoachConnection } from './push-notifications.mjs';
export { emailCoachOnSubmission, emailCoachDeadlineReminders } from './coach-email.mjs';
export { explainTrainingSession, removeSessionExplanation } from './session-explanation.mjs';

initializeApp();
const db = adminBillingStore(getFirestore());
const secretKey = defineSecret("PAYMONGO_SECRET_KEY");
const webhookSecret = defineSecret("PAYMONGO_WEBHOOK_SECRET");
const region = "asia-southeast1";

export const createGcashCheckout = onRequest({ region, secrets: [secretKey], maxInstances: 3, timeoutSeconds: 45 }, async (req, res) => {
  res.set("Cache-Control", "no-store");
  if (req.method !== "POST") return res.status(405).json({ error: "method_not_allowed" });
  const token = req.get("authorization")?.match(/^Bearer (.+)$/)?.[1];
  if (!token) return res.status(401).json({ error: "sign_in_required" });
  let user;
  try { user = await getAuth().verifyIdToken(token, true); }
  catch { return res.status(401).json({ error: "invalid_session" }); }
  if (user.email_verified !== true) return res.status(403).json({ error: "verify_email_required" });

  try {
    return res.json(await startGcashCheckout({ db, uid: user.uid, offer: req.body, secretKey: secretKey.value() }));
  } catch (error) {
    if (error instanceof BillingError) return res.status(error.status).json({ error: error.code });
    logger.error("PayMongo checkout request failed", { reason: error.name === "Error" ? error.message : error.name });
    return res.status(502).json({ error: "checkout_unavailable" });
  }
});

export const paymongoWebhook = onRequest({ region, secrets: [webhookSecret], maxInstances: 3, timeoutSeconds: 30 }, async (req, res) => {
  if (req.method !== "POST") return res.sendStatus(405);
  if (!verifySignature(req.rawBody, req.get("paymongo-signature"), webhookSecret.value())) return res.sendStatus(401);
  const paid = paidCheckout(req.body);
  if (!paid) return res.sendStatus(200);
  try {
    const result = await fulfillGcashPayment(db, paid);
    if (result === "retry") return res.sendStatus(503);
    if (result === "payment_mismatch") logger.error("PayMongo payment mismatch", { sessionId: paid.sessionId });
    return res.sendStatus(200);
  } catch (error) {
    logger.error("PayMongo payment fulfillment failed", { sessionId: paid.sessionId, reason: error.name });
    return res.sendStatus(500);
  }
});
