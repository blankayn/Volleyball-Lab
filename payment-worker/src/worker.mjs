import { Buffer } from "node:buffer";
import { BillingError } from "../../functions/billing-checkout.mjs";
import { FIREBASE_PROJECT } from "../../ai-worker/src/firebase.mjs";
import { firestoreBillingStore } from "./firestore-billing-store.mjs";
import { checkoutUser, firestoreAccessToken } from "./google.mjs";
import { handleXenditCallback, startXenditCheckout, validXenditCallbackToken, xenditClient } from "./xendit.mjs";

function json(body, status = 200) {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
}

async function readBody(request, limit) {
  const buffer = Buffer.alloc(limit);
  let length = 0;
  if (!request.body) return buffer.subarray(0, 0);
  const reader = request.body.getReader();
  while (true) {
    const { value, done } = await reader.read();
    if (done) return buffer.subarray(0, length);
    if (length + value.byteLength > limit) {
      await reader.cancel();
      throw new BillingError(413, "request_too_large");
    }
    buffer.set(value, length);
    length += value.byteLength;
  }
}

function parseJson(body) {
  try { return JSON.parse(body.toString("utf8")); }
  catch { throw new BillingError(400, "invalid_json"); }
}

async function database(env) {
  const token = await firestoreAccessToken(env.FIRESTORE_SERVICE_ACCOUNT);
  return firestoreBillingStore({ projectId: FIREBASE_PROJECT, token });
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    const checkout = path === "/createGcashCheckout";
    if (!checkout && path !== "/xenditWebhook") return json({ error: "not_found" }, 404);
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
    if (!env.FIRESTORE_SERVICE_ACCOUNT || !env.XENDIT_WEBHOOK_TOKEN || !env.XENDIT_SECRET_KEY ||
        (checkout && !env.FIREBASE_WEB_API_KEY)) {
      return json({ error: "checkout_not_configured" }, 503);
    }
    try {
      const xendit = xenditClient(env.XENDIT_SECRET_KEY);
      if (checkout) {
        const uid = await checkoutUser(request.headers.get("authorization"), env.FIREBASE_WEB_API_KEY);
        const offer = parseJson(await readBody(request, 4096));
        return json(await startXenditCheckout({
          db: await database(env), uid, offer, request: xendit,
        }));
      }

      if (!validXenditCallbackToken(request.headers.get("x-callback-token"), env.XENDIT_WEBHOOK_TOKEN)) {
        return json({ error: "invalid_callback_token" }, 401);
      }
      const event = parseJson(await readBody(request, 128 * 1024));
      if (event?.event !== "payment_session.completed") return json({ received: true });
      const result = await handleXenditCallback(await database(env), xendit, event);
      if (result === "retry") return json({ error: "checkout_not_ready" }, 503);
      if (result === "payment_mismatch") console.error("Xendit payment mismatch");
      return json({ received: true });
    } catch (error) {
      if (error instanceof BillingError) return json({ error: error.code }, error.status);
      console.error("Payment request failed", error.name === "Error" ? error.message : error.name);
      return json({ error: checkout ? "checkout_unavailable" : "webhook_unavailable" }, checkout ? 502 : 500);
    }
  },
};
