import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { validConfig, verifySignature, paidCheckout, expiryForPayment } from "../billing-policy.mjs";

test("checkout remains disabled until live merchant price and return URL exist", () => {
  assert.equal(validConfig({}), false);
  const cfg = { enabled: true, liveMode: true, priceCentavos: 19900, durationDays: 30, returnUrl: "https://volleyball-lab.web.app/payment-return.html" };
  assert.equal(validConfig(cfg), true);
  for (const patch of [{ priceCentavos: 0 }, { liveMode: false }, { durationDays: -1 }, { returnUrl: "http://bad" }]) assert.equal(validConfig({ ...cfg, ...patch }), false);
});
test("signature verifies original bytes and rejects tampering, test mode and replay", () => {
  const raw = Buffer.from('{"paid":true}'); const now = 1700000000;
  const signature = createHmac("sha256", "secret").update(`${now}.`).update(raw).digest("hex");
  const header = `t=${now},te=,li=${signature}`;
  assert.equal(verifySignature(raw, header, "secret", now), true);
  assert.equal(verifySignature(Buffer.from('{"paid":false}'), header, "secret", now), false);
  assert.equal(verifySignature(raw, header, "secret", now + 301), false);
  assert.equal(verifySignature(raw, `t=${now},te=${signature},li=`, "secret", now), false);
});
test("only a live paid GCash payment produces an entitlement candidate", () => {
  const payload = { data: { type: "checkout_session.payment.paid", livemode: true, data: { id: "cs_123", attributes: {
    reference_number: "order1", payments: [{ id: "pay_1", attributes: { status: "paid", currency: "PHP", amount: 19900, source: { type: "gcash" } } }],
  } } } };
  assert.equal(paidCheckout(payload).amount, 19900);
  const clone = structuredClone(payload); clone.data.livemode = false; assert.equal(paidCheckout(clone), null);
  clone.data.livemode = true; clone.data.data.attributes.payments[0].attributes.status = "pending"; assert.equal(paidCheckout(clone), null);
  clone.data.data.attributes.payments[0].attributes.status = "paid"; clone.data.data.attributes.payments[0].attributes.source.type = "card"; assert.equal(paidCheckout(clone), null);
  assert.equal(paidCheckout({ data: { attributes: payload.data } }).sessionId, "cs_123");
});
test("renewal extends existing time and preserves permanent entitlement", () => {
  assert.equal(expiryForPayment(30, 1000, { status: "active", expiresAtEpochMs: 2000 }), 2000 + 30 * 86400000);
  assert.equal(expiryForPayment(30, 1000, { status: "active", expiresAtEpochMs: 0 }), 0);
  assert.equal(expiryForPayment(30, 1000, { status: "revoked", expiresAtEpochMs: 0 }), 1000 + 30 * 86400000);
  assert.equal(expiryForPayment(30, 1000, { status: "revoked", expiresAtEpochMs: 9000 }), 1000 + 30 * 86400000);
  assert.equal(expiryForPayment(0, 1000, null), 0);
});
