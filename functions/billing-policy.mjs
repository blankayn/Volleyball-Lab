import { createHmac, timingSafeEqual } from "node:crypto";

export const OWNER_UID = "4j06UlYqbrbLzujmfi8VW5yEIOq2";

export function validConfig(config) {
  return config?.enabled === true && config.liveMode === true &&
    Number.isSafeInteger(config.priceCentavos) && config.priceCentavos >= 100 && config.priceCentavos <= 10_000_000 &&
    Number.isInteger(config.durationDays) && config.durationDays >= 0 && config.durationDays <= 365 &&
    typeof config.returnUrl === "string" && /^https:\/\//.test(config.returnUrl);
}

export function verifySignature(rawBody, header, secret, nowSeconds = Date.now() / 1000) {
  if (!Buffer.isBuffer(rawBody) || typeof header !== "string" || !secret) return false;
  const parts = Object.fromEntries(header.split(",").map(part => part.trim().split("=")));
  const timestamp = Number(parts.t);
  if (!Number.isFinite(timestamp) || Math.abs(nowSeconds - timestamp) > 300 || !/^[a-f0-9]{64}$/.test(parts.li ?? "")) return false;
  const expected = createHmac("sha256", secret).update(`${parts.t}.`).update(rawBody).digest();
  return timingSafeEqual(expected, Buffer.from(parts.li, "hex"));
}

/** Supports the documented current event envelope and the original v1 envelope. */
export function paidCheckout(event) {
  const envelope = event?.data?.attributes ?? event?.data;
  if (envelope?.type !== "checkout_session.payment.paid" || envelope.livemode !== true) return null;
  const session = envelope.data;
  if (typeof session?.id !== "string" || !/^cs_[A-Za-z0-9]+$/.test(session.id)) return null;
  const payments = session.attributes?.payments;
  if (!Array.isArray(payments)) return null;
  const paid = payments.find(p => p.attributes?.status === "paid" && p.attributes.currency === "PHP" &&
    p.attributes.source?.type === "gcash" && Number.isSafeInteger(p.attributes.amount) && typeof p.id === "string");
  if (!paid) return null;
  return { sessionId: session.id, paymentId: paid.id, amount: paid.attributes.amount,
    reference: session.attributes.reference_number };
}

export function expiryForPayment(durationDays, now, existingEntitlement) {
  const existingExpiry = existingEntitlement?.status === "active" ? existingEntitlement.expiresAtEpochMs : undefined;
  if (durationDays === 0 || existingExpiry === 0) return 0;
  return Math.max(now, typeof existingExpiry === "number" ? existingExpiry : 0) + durationDays * 86_400_000;
}
