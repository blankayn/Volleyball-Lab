import test, { after } from "node:test";
import assert from "node:assert/strict";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { fulfillGcashPayment, startGcashCheckout } from "../billing-checkout.mjs";
import { paidCheckout } from "../billing-policy.mjs";
import { adminBillingStore } from "../admin-billing-store.mjs";
import { firestoreBillingStore } from "../../payment-worker/src/firestore-billing-store.mjs";

if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error("Run with the Firestore emulator.");
const testRest = process.env.PAYMENT_STORE === "rest";
const projectId = testRest ? "demo-volleyball-billing-rest" : "demo-volleyball-billing";
const app = initializeApp({ projectId }, "billing-test");
const db = getFirestore(app);
const store = testRest
  ? firestoreBillingStore({ projectId, token: "owner", origin: `http://${process.env.FIRESTORE_EMULATOR_HOST}` })
  : adminBillingStore(db);
after(async () => { await db.terminate(); await deleteApp(app); });

const config = { enabled: true, liveMode: true, priceCentavos: 19900, durationDays: 30,
  returnUrl: "https://volleyball-lab.web.app/payment-return.html" };
const secretKey = "sk_live_fixture";
const checkoutUrl = "https://checkout.paymongo.com/cs_fixture#public-key";

function session(reference, attributes = {}) {
  return { id: "cs_fixture", attributes: { livemode: true, reference_number: reference, status: "active",
    checkout_url: checkoutUrl, payments: [], ...attributes } };
}

function payment(amount) {
  return { id: "pay_fixture", attributes: { status: "paid", currency: "PHP", amount, source: { type: "gcash" } } };
}

test("an uncertain create retries the same provider request and matching paid delivery grants Pro once", async () => {
  await db.doc("billingConfig/pro").set(config);
  const requests = [];
  const uid = "billing-retry";
  const fetcher = async (url, request) => {
    assert.equal(url, "https://api.paymongo.com/v2/checkout_sessions");
    requests.push(request);
    if (requests.length === 1) throw new Error("network timeout");
    return Response.json({ data: session(request.headers["Idempotency-Key"]) });
  };
  const start = () => startGcashCheckout({ db: store, uid, offer: config, secretKey, fetcher });
  await assert.rejects(start, /network timeout/);
  const { orderId } = (await db.doc(`billingPending/${uid}`).get()).data();
  assert.deepEqual(await start(), { checkoutUrl });
  const [first, second] = requests;
  assert.equal(first.headers["Idempotency-Key"], orderId);
  assert.deepEqual(first.headers, second.headers);
  assert.equal(first.body, second.body);
  const sent = JSON.parse(second.body).data.attributes;
  assert.equal(sent.reference_number, orderId);
  assert.equal(sent.line_items[0].amount, config.priceCentavos);
  assert.deepEqual(sent.payment_method_types, ["gcash"]);

  const event = amount => paidCheckout({ data: { type: "checkout_session.payment.paid", livemode: true,
    data: session(orderId, { payments: [payment(amount)] }) } });
  assert.equal(await fulfillGcashPayment(store, event(config.priceCentavos - 1)), "payment_mismatch");
  const entitlementRef = db.doc(`entitlements/${uid}`);
  assert.equal((await entitlementRef.get()).exists, false);
  assert.equal(await fulfillGcashPayment(store, event(config.priceCentavos)), "paid");
  const entitlement = (await entitlementRef.get()).data();
  assert.equal(entitlement.expiresAtEpochMs, entitlement.updatedAt + 30 * 86_400_000);
  assert.equal(await fulfillGcashPayment(store, event(config.priceCentavos)), "paid");
  assert.deepEqual((await entitlementRef.get()).data(), entitlement);
  assert.equal((await db.doc(`billingPending/${uid}`).get()).exists, false);
  assert.deepEqual(await start(), { status: "pro_active" });
  assert.equal(requests.length, 2);
});

test("checkout rejects stale offers and replaces a legacy session only after PayMongo expires it", async () => {
  const uid = "billing-legacy";
  const createdAt = Date.now();
  await db.doc("billingConfig/pro").set(config);
  await db.doc("billingOrders/legacyOrder").set({ ownerUid: uid, status: "pending", checkoutSessionId: "cs_fixture",
    currency: "PHP", priceCentavos: 10000, durationDays: 30, createdAt });
  await db.doc(`billingPending/${uid}`).set({ ownerUid: uid, lastRequestedAt: createdAt, checkoutUrl });

  const calls = [];
  let canExpire = false;
  let replacementOrderId;
  const fetcher = async (url, request) => {
    const path = new URL(url).pathname;
    calls.push(path);
    if (path.endsWith("/expire")) {
      assert.equal(request.method, "POST");
      if (!canExpire) return new Response(null, { status: 400 });
      return Response.json({ data: session("legacyOrder", { status: "expired" }) });
    }
    if (path === "/v1/checkout_sessions/cs_fixture") return Response.json({ data: session("legacyOrder") });
    if (path === "/v1/checkout_sessions/cs_replacement") {
      return Response.json({ data: { ...session(replacementOrderId, { payments: [
        { ...payment(config.priceCentavos), id: "pay_replacement" },
      ] }), id: "cs_replacement" } });
    }
    replacementOrderId = request.headers["Idempotency-Key"];
    return Response.json({ data: { ...session(replacementOrderId), id: "cs_replacement" } });
  };
  const start = offer => startGcashCheckout({ db: store, uid, offer, secretKey, fetcher });
  await assert.rejects(() => start({ ...config, priceCentavos: 10000 }), { code: "offer_changed" });
  assert.deepEqual(calls, []);
  await assert.rejects(() => start(config), { code: "payment_processing" });
  assert.equal((await db.doc("billingOrders/legacyOrder").get()).data().status, "pending");
  assert.deepEqual(calls.splice(0), ["/v1/checkout_sessions/cs_fixture", "/v1/checkout_sessions/cs_fixture/expire",
    "/v1/checkout_sessions/cs_fixture"]);
  canExpire = true;
  assert.deepEqual(await start(config), { checkoutUrl });
  assert.deepEqual(calls, ["/v1/checkout_sessions/cs_fixture", "/v1/checkout_sessions/cs_fixture/expire", "/v2/checkout_sessions"]);
  assert.equal((await db.doc("billingOrders/legacyOrder").get()).data().status, "expired");

  // A paid session can be recovered even if the webhook has not arrived.
  assert.deepEqual(await start(config), { status: "pro_active" });
  assert.equal(calls.at(-1), "/v1/checkout_sessions/cs_replacement");
  assert.equal((await db.doc(`entitlements/${uid}`).get()).data().source, "paymongo_gcash");
});
