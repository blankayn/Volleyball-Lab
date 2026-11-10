import { Buffer } from "node:buffer";
import { createHash, timingSafeEqual } from "node:crypto";
import { BillingError, fulfillGcashPayment, gcashOrderStore } from "../../functions/billing-checkout.mjs";
import { httpsUrl } from "../../functions/billing-policy.mjs";

const sessionLifetime = 30 * 60_000;
const sessionId = value => typeof value === "string" && /^ps-[a-z0-9-]{8,64}$/i.test(value);
const paymentId = value => typeof value === "string" && /^py-[a-z0-9-]{8,64}$/i.test(value);
const requestId = value => typeof value === "string" && /^pr-[a-z0-9-]{8,64}$/i.test(value);
const referenceId = value => typeof value === "string" && /^[a-f0-9]{32}$/.test(value);

function centavos(value) {
  if (typeof value !== "number" || !/^\d+(\.\d{1,2})?$/.test(String(value))) return null;
  const result = Math.round(value * 100);
  return Number.isSafeInteger(result) && result > 0 ? result : null;
}

function liveCheckoutUrl(session) {
  const value = session.payment_link_url;
  const origin = httpsUrl(value)?.origin;
  // Test mode has distinct origins. Never expose a test session from this Worker.
  if (origin !== "https://checkout.xendit.co" && origin !== "https://xen.to") {
    throw new Error("invalid_live_xendit_checkout_url");
  }
  return value;
}

function isGcashSession(session, id) {
  return sessionId(id) && session?.payment_session_id === id && referenceId(session.reference_id) &&
    session.session_type === "PAY" && session.mode === "PAYMENT_LINK" && session.country === "PH" &&
    session.currency === "PHP" && centavos(session.amount) !== null;
}

export function validXenditCallbackToken(header, secret) {
  if (!header || !secret) return false;
  const digest = value => createHash("sha256").update(value).digest();
  return timingSafeEqual(digest(header), digest(secret));
}

export function xenditClient(secretKey, fetcher = fetch) {
  const authorization = `Basic ${Buffer.from(`${secretKey}:`).toString("base64")}`;
  return async function request(path, body) {
    const response = await fetcher(`https://api.xendit.co${path}`, {
      method: body ? "POST" : "GET",
      redirect: "manual", signal: AbortSignal.timeout(8_000),
      headers: {
        Authorization: authorization,
        ...(body && { "Content-Type": "application/json" }),
        ...(path.startsWith("/v3/") && { "api-version": "2024-11-11" }),
      },
      body: body && JSON.stringify(body),
    });
    if (!response.ok) {
      const error = new Error(`xendit_http_${response.status}`);
      error.httpStatus = response.status;
      throw error;
    }
    return response.json();
  };
}

async function getSession(request, id) {
  if (!sessionId(id)) throw new Error("invalid_xendit_session_id");
  const session = await request(`/sessions/${id}`);
  if (!isGcashSession(session, id)) throw new Error("invalid_xendit_session");
  return session;
}

async function confirmPayment(db, request, session) {
  if (session.status !== "COMPLETED" || !paymentId(session.payment_id)) {
    throw new BillingError(409, "payment_processing");
  }
  const payment = await request(`/v3/payments/${session.payment_id}`);
  const linkedRequest = session.payment_request_id ?? session.latest_payment_request_id;
  const amount = centavos(session.amount);
  if (payment.status !== "SUCCEEDED") throw new BillingError(409, "payment_processing");
  if (payment.payment_id !== session.payment_id || !requestId(linkedRequest) || payment.payment_request_id !== linkedRequest ||
      !session.business_id || payment.business_id !== session.business_id || payment.channel_code !== "GCASH" ||
      payment.type !== "PAY" || payment.country !== "PH" || payment.currency !== "PHP" ||
      centavos(payment.request_amount) !== amount || payment.captures?.length !== 1 ||
      centavos(payment.captures[0]?.capture_amount) !== amount) {
    return "payment_mismatch";
  }
  return fulfillGcashPayment(db, {
    provider: "xendit", reference: session.reference_id, sessionId: session.payment_session_id,
    paymentId: payment.payment_id, amount,
  });
}

export async function handleXenditCallback(db, request, event) {
  const id = event?.data?.payment_session_id;
  if (event?.event !== "payment_session.completed" || !sessionId(id)) return "ignored";
  return confirmPayment(db, request, await getSession(request, id));
}

export async function startXenditCheckout({ db, uid, offer, request }) {
  const orders = gcashOrderStore(db, uid, "xendit", sessionLifetime);
  for (let attempt = 0; attempt < 2; attempt++) {
    const reservation = await orders.reserve(offer);
    if (!reservation || reservation.order.status === "paid") return { status: "pro_active" };
    const { order, orderId, config, created } = reservation;
    if (created) {
      let session;
      try {
        session = await request("/sessions", {
          reference_id: orderId, session_type: "PAY", mode: "PAYMENT_LINK",
          amount: order.priceCentavos / 100, currency: "PHP", country: "PH",
          capture_method: "AUTOMATIC", allow_save_payment_method: "DISABLED",
          allowed_payment_channels: ["GCASH"],
          expires_at: new Date(order.createExpiresAt).toISOString(),
          description: order.durationDays ? `Volleyball Lab Pro · ${order.durationDays} days` : "Volleyball Lab Pro",
          success_return_url: order.returnUrl, cancel_return_url: order.returnUrl,
        });
      } catch (error) {
        // Xendit does not document idempotent creation for Sessions. Retry only after
        // a known rejection or after the original, unexposed session would expire.
        if ([400, 401, 403, 404, 422].includes(error.httpStatus)) await orders.retire(orderId);
        throw error;
      }
      if (!isGcashSession(session, session?.payment_session_id) || session.reference_id !== orderId ||
          centavos(session.amount) !== order.priceCentavos || session.status !== "ACTIVE") {
        throw new Error("invalid_xendit_session");
      }
      const checkoutUrl = liveCheckoutUrl(session);
      return await orders.attach(orderId, session.payment_session_id) ? { checkoutUrl } : { status: "pro_active" };
    }

    if (!order.checkoutSessionId) throw new BillingError(409, "checkout_retry");
    if (order.status !== "pending") throw new BillingError(409, "checkout_needs_review");
    const session = await getSession(request, order.checkoutSessionId);
    if (session.reference_id !== orderId || centavos(session.amount) !== order.priceCentavos) {
      throw new BillingError(409, "checkout_needs_review");
    }
    if (session.status === "COMPLETED") {
      if (await confirmPayment(db, request, session) !== "paid") throw new BillingError(409, "checkout_needs_review");
      return { status: "pro_active" };
    }
    if (session.status === "ACTIVE") {
      if (order.priceCentavos !== config.priceCentavos || order.durationDays !== config.durationDays ||
          order.returnUrl !== config.returnUrl) throw new BillingError(409, "previous_checkout_open");
      return { checkoutUrl: liveCheckoutUrl(session) };
    }
    if (!["EXPIRED", "CANCELED"].includes(session.status)) throw new Error("unexpected_xendit_session_status");

    // An attempt started just before checkout closed may still be payable in GCash.
    const lastRequest = session.latest_payment_request_id ?? session.payment_request_id;
    if (lastRequest) {
      if (!requestId(lastRequest)) throw new Error("invalid_xendit_payment_request_id");
      const paymentRequest = await request(`/v3/payment_requests/${lastRequest}`);
      if (paymentRequest.payment_request_id !== lastRequest ||
          !["CANCELED", "EXPIRED", "FAILED"].includes(paymentRequest.status)) {
        throw new BillingError(409, "payment_processing");
      }
    }
    await orders.retire(orderId, order.checkoutSessionId);
  }
  throw new BillingError(409, "checkout_retry");
}
