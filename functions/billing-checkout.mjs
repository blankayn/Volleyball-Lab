import { Buffer } from "node:buffer";
import { expiryForPayment, httpsUrl, OWNER_UID, paidGcashSession, validConfig } from "./billing-policy.mjs";

export class BillingError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

const sameOffer = (a, b) => a?.priceCentavos === b.priceCentavos && a?.durationDays === b.durationDays;
const providerOf = order => order.provider ?? "paymongo";
const checkoutOrigin = "https://checkout.paymongo.com";
// PayMongo keeps idempotency results for 24 hours. Leave a margin for in-flight requests.
const createRetryWindow = 23 * 60 * 60 * 1000;

export function gcashOrderStore(db, uid, provider, createWindowMs = 0) {
  const pendingPath = `billingPending/${uid}`;
  const deletionPath = `accountDeletions/${uid}`;
  async function reserve(offer) {
    return db.runTransaction(async tx => {
      const [deletion, entitlement, config, pending] = await tx.getAll(
        deletionPath, `entitlements/${uid}`, "billingConfig/pro", pendingPath,
      );
      if (deletion) throw new BillingError(403, "account_deleting");
      if (uid === OWNER_UID || (entitlement?.status === "active" &&
          (entitlement.expiresAtEpochMs === 0 || entitlement.expiresAtEpochMs > Date.now()))) return null;
      if (!validConfig(config)) throw new BillingError(503, "checkout_not_configured");
      if (!sameOffer(offer, config)) throw new BillingError(409, "offer_changed");

      if (pending?.orderId) {
        const [order] = await tx.getAll(`billingOrders/${pending.orderId}`);
        if (order?.ownerUid !== uid || providerOf(order) !== provider) {
          throw new BillingError(409, "checkout_needs_review");
        }
        if (createWindowMs && order.status === "creating" && !order.checkoutSessionId &&
            order.createExpiresAt <= Date.now()) {
          // No checkout URL is exposed until the session ID is saved.
          tx.update(`billingOrders/${pending.orderId}`, { status: "expired" });
        } else {
          return { orderId: pending.orderId, order, config };
        }
      }
      if (pending?.checkoutUrl) {
        if (provider !== "paymongo") throw new BillingError(409, "checkout_needs_review");
        // The original pending document stored the URL and timestamp, but no order ID.
        const legacy = await tx.findLegacyOrder(uid, pending.lastRequestedAt);
        if (!legacy?.order.checkoutSessionId) throw new BillingError(409, "checkout_needs_review");
        tx.set(pendingPath, { ownerUid: uid, orderId: legacy.orderId });
        return { ...legacy, config };
      }

      const orderId = crypto.randomUUID().replaceAll("-", "");
      const createdAt = Date.now();
      const order = { ownerUid: uid, provider, priceCentavos: config.priceCentavos, durationDays: config.durationDays,
        returnUrl: config.returnUrl, currency: "PHP", status: "creating", createdAt };
      if (createWindowMs) order.createExpiresAt = createdAt + createWindowMs;
      tx.set(pendingPath, { ownerUid: uid, orderId });
      tx.set(`billingOrders/${orderId}`, order);
      return { orderId, order, config, created: true };
    });
  }

  async function attach(orderId, sessionId) {
    return db.runTransaction(async tx => {
      const orderPath = `billingOrders/${orderId}`;
      const [deletion, current, pending] = await tx.getAll(deletionPath, orderPath, pendingPath);
      if (deletion) throw new BillingError(403, "account_deleting");
      if (current?.status === "paid") return false;
      if (pending?.orderId !== orderId || current?.ownerUid !== uid || providerOf(current) !== provider ||
          !["creating", "pending"].includes(current.status) ||
          (createWindowMs && current.createExpiresAt <= Date.now())) {
        throw new BillingError(409, "checkout_retry");
      }
      if (current.checkoutSessionId && current.checkoutSessionId !== sessionId) throw new Error("checkout_session_changed");
      tx.update(orderPath, { status: "pending", checkoutSessionId: sessionId });
      tx.set(`billingSessions/${sessionId}`, { orderId });
      return true;
    });
  }

  async function retire(orderId, sessionId) {
    await db.runTransaction(async tx => {
      const orderPath = `billingOrders/${orderId}`;
      const [order, pending] = await tx.getAll(orderPath, pendingPath);
      if (order?.ownerUid === uid && providerOf(order) === provider && pending?.orderId === orderId &&
          order.checkoutSessionId === sessionId && order.status === (sessionId ? "pending" : "creating")) {
        tx.update(orderPath, { status: "expired" });
        tx.delete(pendingPath);
      }
    });
  }

  return { reserve, attach, retire };
}

export async function startGcashCheckout({ db, uid, offer, secretKey, fetcher = fetch }) {
  if (!secretKey.startsWith("sk_live_")) throw new BillingError(503, "checkout_not_configured");
  const orders = gcashOrderStore(db, uid, "paymongo");
  const authorization = `Basic ${Buffer.from(`${secretKey}:`).toString("base64")}`;
  const request = (path, options = {}) => fetcher(`https://api.paymongo.com${path}`, {
    ...options, redirect: "manual", signal: AbortSignal.timeout(8_000),
    headers: { Authorization: authorization, ...options.headers },
  });

  async function createSession(orderId, order) {
    if (order.status !== "creating" || Date.now() - order.createdAt >= createRetryWindow) {
      throw new BillingError(409, "checkout_needs_review");
    }
    const response = await request("/v2/checkout_sessions", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": orderId },
      body: JSON.stringify({ data: { attributes: {
        line_items: [{ name: "Volleyball Lab Pro", amount: order.priceCentavos, currency: order.currency, quantity: 1 }],
        payment_method_types: ["gcash"], reference_number: orderId,
        success_url: order.returnUrl, cancel_url: order.returnUrl, send_email_receipt: true, show_line_items: true,
      } } }),
    });
    const session = await checkoutResource(response);
    if (typeof session.id !== "string" || !/^cs_[A-Za-z0-9]+$/.test(session.id) || session.attributes.livemode !== true) {
      throw new Error("invalid_checkout_session");
    }
    checkoutUrl(session);
    return await orders.attach(orderId, session.id) ? session : null;
  }

  // A provider-expired order can be replaced once in the same request.
  for (let attempt = 0; attempt < 2; attempt++) {
    const reserved = await orders.reserve(offer);
    if (!reserved || reserved.order.status === "paid") return { status: "pro_active" };
    const { orderId, order, config } = reserved;
    const sameTerms = sameOffer(order, config) && order.returnUrl === config.returnUrl;
    if (!order.checkoutSessionId) {
      const session = await createSession(orderId, order);
      if (!session) return { status: "pro_active" };
      if (sameTerms) return { checkoutUrl: checkoutUrl(session) };
      // Recover the old session first so that changing terms cannot create two payable sessions.
      order.checkoutSessionId = session.id;
    }
    if (!/^cs_[A-Za-z0-9]+$/.test(order.checkoutSessionId)) throw new Error("invalid_checkout_session_id");
    const path = `/v1/checkout_sessions/${order.checkoutSessionId}`;
    let session = await checkoutResource(await request(path));
    if (session.id !== order.checkoutSessionId || session.attributes.reference_number !== orderId ||
        session.attributes.livemode !== true) throw new Error("checkout_order_mismatch");
    const paid = paidGcashSession(session);
    if (paid) {
      if (await fulfillGcashPayment(db, paid) !== "paid") throw new BillingError(409, "checkout_needs_review");
      return { status: "pro_active" };
    }
    if (session.attributes.payments?.some(payment => payment?.attributes?.status === "paid") ||
        session.attributes.payment_intent?.attributes?.status === "succeeded") {
      throw new BillingError(409, "payment_processing");
    }
    if (session.attributes?.status === "active") {
      if (sameTerms) return { checkoutUrl: checkoutUrl(session) };
      const expired = await request(`${path}/expire`, { method: "POST" });
      session = expired.status === 400 ? await checkoutResource(await request(path)) : await checkoutResource(expired);
      if (session.id !== order.checkoutSessionId || session.attributes.status !== "expired") {
        throw new BillingError(409, "payment_processing");
      }
    }
    if (session.attributes?.status !== "expired") throw new Error("unexpected_checkout_status");
    await orders.retire(orderId, order.checkoutSessionId);
  }
  throw new BillingError(409, "checkout_retry");
}

async function checkoutResource(response) {
  if (!response.ok) throw new Error(`paymongo_http_${response.status}`);
  const { data } = await response.json();
  if (!data?.attributes) throw new Error("invalid_checkout_response");
  return data;
}

function checkoutUrl(session) {
  const value = session.attributes.checkout_url;
  if (httpsUrl(value)?.origin !== checkoutOrigin) throw new Error("invalid_checkout_url");
  return value;
}

export async function fulfillGcashPayment(db, paid) {
  return db.runTransaction(async tx => {
    const orderPath = `billingOrders/${paid.reference}`;
    const [order] = await tx.getAll(orderPath);
    if (!order) return "unknown_order";
    const provider = paid.provider ?? "paymongo";
    if (providerOf(order) !== provider) return "payment_mismatch";
    if (order.status === "paid" && order.checkoutSessionId === paid.sessionId &&
        order.paymentId === paid.paymentId && order.priceCentavos === paid.amount) return "paid";
    if (order.status === "creating") return "retry";
    const canFulfill = order.status === "pending" || (provider === "xendit" && order.status === "expired");
    if (!canFulfill || order.checkoutSessionId !== paid.sessionId ||
        order.priceCentavos !== paid.amount || order.currency !== "PHP") return "payment_mismatch";

    const entitlementPath = `entitlements/${order.ownerUid}`;
    const paymentPath = `billingPayments/${paid.paymentId}`;
    const pendingPath = `billingPending/${order.ownerUid}`;
    const [deletion, existing, payment, pending] = await tx.getAll(
      `accountDeletions/${order.ownerUid}`, entitlementPath, paymentPath, pendingPath,
    );
    if (deletion) return "account_deleting";
    if (payment) return "payment_mismatch";
    const now = Date.now();
    tx.set(paymentPath, { orderId: paid.reference, receivedAt: new Date(now) });
    tx.update(orderPath, { status: "paid", paymentId: paid.paymentId, paidAt: now });
    tx.set(entitlementPath, { ownerUid: order.ownerUid, status: "active", source: `${provider}_gcash`,
      expiresAtEpochMs: expiryForPayment(order.durationDays, now, existing), updatedAt: now });
    if (pending?.orderId === paid.reference || (!pending?.orderId && pending?.lastRequestedAt === order.createdAt)) {
      tx.delete(pendingPath);
    }
    return "paid";
  });
}
