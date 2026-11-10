import test, { after } from "node:test";
import assert from "node:assert/strict";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { fulfillGcashPayment } from "../billing-checkout.mjs";
import { firestoreBillingStore } from "../../payment-worker/src/firestore-billing-store.mjs";
import worker from "../../payment-worker/src/worker.mjs";
import { handleXenditCallback, startXenditCheckout, xenditClient } from "../../payment-worker/src/xendit.mjs";

if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error("Run with the Firestore emulator.");
const projectId = "demo-volleyball-xendit";
const app = initializeApp({ projectId }, "xendit-test");
const db = getFirestore(app);
const store = firestoreBillingStore({ projectId, token: "owner", origin: `http://${process.env.FIRESTORE_EMULATOR_HOST}` });
after(async () => { await db.terminate(); await deleteApp(app); });

const offer = { enabled: true, liveMode: true, priceCentavos: 19900, durationDays: 30,
  returnUrl: "https://volleyball-lab.web.app/payment-return.html" };
const requestId = "pr-1102feb0-bb79-47ae-9d1e-e69394d3949c";
const businessId = "5f27a14a9bf05c73dd040bc8";
const pendingFor = uid => db.doc(`billingPending/${uid}`);
const entitlementFor = uid => db.doc(`entitlements/${uid}`);

function fixture() {
  const paymentId = `py-${crypto.randomUUID()}`;
  let current, payment, paymentRequestStatus, creates = 0;
  const post = [];
  const request = xenditClient("server-fixture", async (url, options) => {
    const path = new URL(url).pathname;
    assert.equal(new URL(url).origin, "https://api.xendit.co");
    assert.equal(options.headers.Authorization, "Basic c2VydmVyLWZpeHR1cmU6");
    if (path === "/sessions") {
      assert.equal(options.method, "POST");
      const payload = JSON.parse(options.body);
      post.push(payload);
      current = {
        payment_session_id: `ps-${String(++creates).padStart(24, "0")}`,
        reference_id: payload.reference_id,
        session_type: payload.session_type, mode: payload.mode, amount: payload.amount,
        country: payload.country, currency: payload.currency, status: "ACTIVE",
        payment_link_url: `https://xen.to/test-case-${creates}`, business_id: businessId,
      };
      return Response.json(current, { status: 201 });
    }
    assert.equal(options.method, "GET");
    if (path === `/sessions/${current.payment_session_id}`) return Response.json(current);
    assert.equal(options.headers["api-version"], "2024-11-11");
    if (path === `/v3/payments/${paymentId}`) return Response.json(payment);
    assert.equal(path, `/v3/payment_requests/${requestId}`);
    return Response.json({ payment_request_id: requestId, status: paymentRequestStatus });
  });
  return {
    request, post, paymentId, current: () => current,
    expire(status) {
      Object.assign(current, { status: "EXPIRED", latest_payment_request_id: requestId });
      paymentRequestStatus = status;
    },
    complete(patch) {
      Object.assign(current, { status: "COMPLETED", payment_id: paymentId, payment_request_id: requestId });
      payment = { payment_id: paymentId, payment_request_id: requestId, business_id: businessId, status: "SUCCEEDED",
        type: "PAY", currency: "PHP", country: "PH", channel_code: "GCASH",
        request_amount: current.amount, captures: [{ capture_amount: current.amount }], ...patch };
    },
  };
}

test("Xendit callback rejects an invalid token before contacting Firestore or Xendit", async () => {
  const response = await worker.fetch(new Request("https://example.test/xenditWebhook", {
    method: "POST", body: JSON.stringify({ event: "payment_session.completed" }),
    headers: { "x-callback-token": "incorrect" },
  }), { FIRESTORE_SERVICE_ACCOUNT: "unused", XENDIT_SECRET_KEY: "unused", XENDIT_WEBHOOK_TOKEN: "secret" });
  assert.equal(response.status, 401);
  assert.deepEqual(await response.json(), { error: "invalid_callback_token" });
});

test("one checkout is issued and only a matching captured GCash payment grants the saved term once", async () => {
  const uid = "xendit-paid";
  await db.doc("billingConfig/pro").set(offer);
  const xendit = fixture();
  let signalStarted, releasePost, firstPost = true;
  const started = new Promise(resolve => { signalStarted = resolve; });
  const held = new Promise(resolve => { releasePost = resolve; });
  const start = () => startXenditCheckout({ db: store, uid, offer, request: async (path, body) => {
    if (body && firstPost) { firstPost = false; signalStarted(); await held; }
    return xendit.request(path, body);
  } });
  const initial = start();
  await Promise.race([started, initial]);
  try { await assert.rejects(start, { code: "checkout_retry" }); }
  finally { releasePost(); }
  const first = await initial;
  assert.deepEqual(first, { checkoutUrl: "https://xen.to/test-case-1" });
  assert.deepEqual(await start(), first);
  assert.equal(xendit.post.length, 1);
  const [payload] = xendit.post;
  assert.equal(payload.amount, 199);
  assert.equal(payload.session_type, "PAY");
  assert.deepEqual(payload.allowed_payment_channels, ["GCASH"]);
  assert.equal(payload.allow_save_payment_method, "DISABLED");

  const callback = () => handleXenditCallback(store, xendit.request, {
    event: "payment_session.completed",
    data: { payment_session_id: xendit.current().payment_session_id, amount: 1 },
  });
  await assert.rejects(callback, { code: "payment_processing" });
  xendit.complete({ captures: [{ capture_amount: 198.99 }] });
  assert.equal(await callback(), "payment_mismatch");
  xendit.complete({ channel_code: "CARDS" });
  assert.equal(await callback(), "payment_mismatch");
  assert.equal(await fulfillGcashPayment(store, {
    reference: payload.reference_id, sessionId: xendit.current().payment_session_id,
    paymentId: xendit.paymentId, amount: 19900,
  }), "payment_mismatch");
  assert.equal((await entitlementFor(uid).get()).exists, false);
  xendit.complete();
  assert.equal(await callback(), "paid");
  const entitlement = (await entitlementFor(uid).get()).data();
  assert.equal(entitlement.source, "xendit_gcash");
  assert.equal(entitlement.expiresAtEpochMs, entitlement.updatedAt + 30 * 86_400_000);
  assert.equal(await callback(), "paid");
  assert.deepEqual((await entitlementFor(uid).get()).data(), entitlement);
  assert.equal((await pendingFor(uid).get()).exists, false);
});

test("changed pricing waits for a payable checkout to expire before starting a replacement", async () => {
  const uid = "xendit-price";
  const config = db.doc("billingConfig/pro");
  await config.set(offer);
  const xendit = fixture();
  const start = displayedOffer => startXenditCheckout({ db: store, uid, offer: displayedOffer, request: xendit.request });
  await start(offer);
  const next = { ...offer, priceCentavos: 21999, durationDays: 45 };
  await config.set(next);
  await assert.rejects(() => start(offer), { code: "offer_changed" });
  await assert.rejects(() => start(next), { code: "previous_checkout_open" });
  xendit.expire("REQUIRES_ACTION");
  await assert.rejects(() => start(next), { code: "payment_processing" });
  assert.equal(xendit.post.length, 1);
  xendit.expire("EXPIRED");
  assert.deepEqual(await start(next), { checkoutUrl: "https://xen.to/test-case-2" });
  assert.equal(xendit.post[1].amount, 219.99);
  xendit.complete();
  assert.deepEqual(await start(next), { status: "pro_active" });
  const entitlement = (await entitlementFor(uid).get()).data();
  assert.equal(entitlement.expiresAtEpochMs, entitlement.updatedAt + 45 * 86_400_000);
});

test("an ambiguous Xendit create cannot be retried until the unused session expires", async () => {
  const uid = "xendit-timeout";
  await db.doc("billingConfig/pro").set(offer);
  const xendit = fixture();
  const start = () => startXenditCheckout({ db: store, uid, offer, request: async (...args) => {
    const response = await xendit.request(...args);
    if (xendit.post.length === 1) throw new Error("provider response lost");
    return response;
  } });
  await assert.rejects(start, /provider response lost/);
  await assert.rejects(start, { code: "checkout_retry" });
  assert.equal(xendit.post.length, 1);
  const { orderId } = (await pendingFor(uid).get()).data();
  await db.doc(`billingOrders/${orderId}`).update({ createExpiresAt: Date.now() - 1000 });
  assert.deepEqual(await start(), { checkoutUrl: "https://xen.to/test-case-2" });
  assert.notEqual(xendit.post[1].reference_id, orderId);
});
