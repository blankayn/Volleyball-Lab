import { Buffer } from "node:buffer";
import { BillingError } from "../../functions/billing-checkout.mjs";
import { FIREBASE_PROJECT } from "../../ai-worker/src/firebase.mjs";

const oauthUrl = "https://oauth2.googleapis.com/token";
const base64url = data => Buffer.from(data).toString("base64url");
let cachedAccess;

export async function firestoreAccessToken(secret, fetcher = fetch) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedAccess?.secret === secret && cachedAccess.expiresAt > now + 60) return cachedAccess.token;

  const account = JSON.parse(secret);
  if (account.project_id !== FIREBASE_PROJECT || typeof account.client_email !== "string" ||
      typeof account.private_key !== "string" || typeof account.private_key_id !== "string") {
    throw new Error("invalid_firestore_service_account");
  }
  const privateKey = Buffer.from(account.private_key.replace(/-----[^-]+-----|\s/g, ""), "base64");
  const algorithm = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" };
  const key = await crypto.subtle.importKey("pkcs8", privateKey, algorithm, false, ["sign"]);
  const claims = {
    iss: account.client_email, aud: oauthUrl, scope: "https://www.googleapis.com/auth/datastore",
    iat: now, exp: now + 300,
  };
  const header = { alg: "RS256", typ: "JWT", kid: account.private_key_id };
  const assertion = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(claims))}`;
  const signature = await crypto.subtle.sign(algorithm, key, Buffer.from(assertion));
  const response = await fetcher(oauthUrl, {
    method: "POST", redirect: "manual", signal: AbortSignal.timeout(6_000),
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${assertion}.${base64url(signature)}`,
    }),
  });
  if (!response.ok) throw new Error(`firestore_auth_${response.status}`);
  const result = await response.json();
  if (typeof result.access_token !== "string" || !Number.isFinite(result.expires_in)) {
    throw new Error("invalid_firestore_token");
  }
  cachedAccess = { secret, token: result.access_token, expiresAt: now + result.expires_in };
  return cachedAccess.token;
}

export async function checkoutUser(authorization, apiKey, fetcher = fetch) {
  const token = /^Bearer ([A-Za-z0-9_.-]{40,16384})$/.exec(authorization ?? "")?.[1];
  if (!token) throw new BillingError(401, "sign_in_required");
  let claims;
  try {
    claims = JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString("utf8"));
  } catch {
    throw new BillingError(401, "invalid_session");
  }
  if (claims?.aud !== FIREBASE_PROJECT || claims.iss !== `https://securetoken.google.com/${FIREBASE_PROJECT}` ||
      typeof claims.sub !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(claims.sub) ||
      !Number.isInteger(claims.auth_time)) throw new BillingError(401, "invalid_session");

  // Identity Toolkit verifies the signature and returns the current account and revocation cutoff.
  const endpoint = new URL("https://identitytoolkit.googleapis.com/v1/accounts:lookup");
  endpoint.searchParams.set("key", apiKey);
  const response = await fetcher(endpoint, {
    method: "POST", redirect: "manual", signal: AbortSignal.timeout(6_000),
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ idToken: token }),
  });
  if (response.status === 400 || response.status === 401) throw new BillingError(401, "invalid_session");
  if (!response.ok) throw new Error(`checkout_identity_${response.status}`);
  const { users } = await response.json();
  const user = users?.[0];
  const validSince = Number(user?.validSince ?? 0);
  if (user?.localId !== claims.sub || user.disabled || !Number.isSafeInteger(validSince) || claims.auth_time < validSince) {
    throw new BillingError(401, "invalid_session");
  }
  if (user.emailVerified !== true) throw new BillingError(403, "verify_email_required");
  return user.localId;
}
