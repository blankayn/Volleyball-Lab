import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { onRequest } from "firebase-functions/v2/https";
import { defineSecret } from "firebase-functions/params";
import { validConfig, verifySignature, paidCheckout, expiryForPayment } from "./billing-policy.mjs";
export { deleteAccount, resumeAccountDeletion } from './account-deletion.mjs';

initializeApp();
const db = getFirestore();
const secretKey = defineSecret("PAYMONGO_SECRET_KEY");
const webhookSecret = defineSecret("PAYMONGO_WEBHOOK_SECRET");
const region = "asia-southeast1";

export const createGcashCheckout = onRequest({ region, secrets: [secretKey], maxInstances: 3, timeoutSeconds: 30 }, async (req, res) => {
  if (req.method !== "POST") return res.status(405).json({ error: "method_not_allowed" });
  const token = req.get("authorization")?.match(/^Bearer (.+)$/)?.[1];
  if (!token) return res.status(401).json({ error: "sign_in_required" });
  let user;
  try { user = await getAuth().verifyIdToken(token, true); }
  catch { return res.status(401).json({ error: "invalid_session" }); }
  if ((await db.doc(`accountDeletions/${user.uid}`).get()).exists) return res.status(403).json({ error: "account_deleting" });
  if (user.email_verified !== true) return res.status(403).json({ error: "verify_email_required" });
  const config = (await db.doc("billingConfig/pro").get()).data();
  if (!validConfig(config) || !secretKey.value().startsWith("sk_live_")) return res.status(503).json({ error: "checkout_not_configured" });
  const now = Date.now();
  // One pending order per account prevents repeated taps from creating multiple checkouts.
  const pending = db.doc(`billingPending/${user.uid}`);
  const current = (await pending.get()).data();
  if (current?.checkoutUrl && current.expiresAt > now && /^https:\/\/checkout\.paymongo\.com\//.test(current.checkoutUrl)) {
    return res.json({ checkoutUrl: current.checkoutUrl });
  }
  const order = db.collection("billingOrders").doc();
  try {
    const allowed = await db.runTransaction(async tx => {
      const state = (await tx.get(pending)).data();
      if ((await tx.get(db.doc(`accountDeletions/${user.uid}`))).exists) return false;
      if (state?.lastRequestedAt && now - state.lastRequestedAt < 60_000) return false;
      tx.set(pending, { ownerUid: user.uid, lastRequestedAt: now });
      tx.set(order, { ownerUid: user.uid, priceCentavos: config.priceCentavos, durationDays: config.durationDays,
        currency: "PHP", status: "creating", createdAt: now });
      return true;
    });
    if (!allowed) return res.status(429).json({ error: "retry_after_one_minute" });
    const response = await fetch("https://api.paymongo.com/v2/checkout_sessions", {
      method: "POST", signal: AbortSignal.timeout(15_000),
      headers: { Authorization: `Basic ${Buffer.from(`${secretKey.value()}:`).toString("base64")}`,
        "Content-Type": "application/json", "Idempotency-Key": order.id },
      body: JSON.stringify({ data: { attributes: {
        line_items: [{ name: "Volleyball Lab Pro", amount: config.priceCentavos, currency: "PHP", quantity: 1 }],
        payment_method_types: ["gcash"], reference_number: order.id,
        success_url: config.returnUrl, cancel_url: config.returnUrl, send_email_receipt: true,
      } } }),
    });
    if (!response.ok) throw new Error("provider_rejected_checkout");
    const checkout = (await response.json()).data;
    const url = checkout?.attributes?.checkout_url;
    if (typeof checkout?.id !== "string" || !/^cs_[A-Za-z0-9]+$/.test(checkout.id) || typeof url !== "string" ||
      new URL(url).origin !== "https://checkout.paymongo.com") throw new Error("invalid_checkout_response");
    await db.runTransaction(async tx => {
      tx.update(order, { status: "pending", checkoutSessionId: checkout.id });
      tx.set(db.doc(`billingSessions/${checkout.id}`), { orderId: order.id });
      tx.set(pending, { ownerUid: user.uid, lastRequestedAt: now, checkoutUrl: url, expiresAt: now + 1_800_000 });
    });
    return res.json({ checkoutUrl: url });
  } catch {
    await order.set({ status: "failed" }, { merge: true });
    return res.status(502).json({ error: "checkout_unavailable" });
  }
});

export const paymongoWebhook = onRequest({ region, secrets: [webhookSecret], maxInstances: 3, timeoutSeconds: 30 }, async (req, res) => {
  if (req.method !== "POST") return res.sendStatus(405);
  if (!verifySignature(req.rawBody, req.get("paymongo-signature"), webhookSecret.value())) return res.sendStatus(401);
  const paid = paidCheckout(req.body);
  if (!paid) return res.sendStatus(200);
  try {
    const session = (await db.doc(`billingSessions/${paid.sessionId}`).get()).data();
    if (!session?.orderId || paid.reference !== session.orderId) return res.sendStatus(400);
    await db.runTransaction(async tx => {
      const orderRef = db.doc(`billingOrders/${session.orderId}`);
      const order = (await tx.get(orderRef)).data();
      if (!order || order.status === "paid") return;
      if ((await tx.get(db.doc(`accountDeletions/${order.ownerUid}`))).exists) return;
      if (order.checkoutSessionId !== paid.sessionId || order.priceCentavos !== paid.amount || order.currency !== "PHP") throw new Error("payment_mismatch");
      const entitlement = db.doc(`entitlements/${order.ownerUid}`);
      const existing = (await tx.get(entitlement)).data();
      const paymentRef = db.doc(`billingPayments/${paid.paymentId}`);
      if ((await tx.get(paymentRef)).exists) return;
      const now = Date.now();
      tx.set(paymentRef, { orderId: session.orderId, receivedAt: FieldValue.serverTimestamp() });
      tx.update(orderRef, { status: "paid", paymentId: paid.paymentId, paidAt: now });
      tx.set(entitlement, { ownerUid: order.ownerUid, status: "active", source: "paymongo_gcash",
        expiresAtEpochMs: expiryForPayment(order.durationDays, now, existing), updatedAt: now });
      tx.delete(db.doc(`billingPending/${order.ownerUid}`));
    });
    return res.sendStatus(200);
  } catch { return res.sendStatus(500); }
});
