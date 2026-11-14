import { Buffer } from "node:buffer";

const PROJECT = "volleyball-lab";
const oauthUrl = "https://oauth2.googleapis.com/token";
const base64url = value => Buffer.from(value).toString("base64url");
let cached;

async function accessToken(secret, fetchImpl) {
  const now = Math.floor(Date.now() / 1_000);
  if (cached?.secret === secret && cached.expiresAt > now + 60) return cached.token;
  const account = JSON.parse(secret);
  if (account.project_id !== PROJECT || !account.client_email?.endsWith(".iam.gserviceaccount.com") ||
      typeof account.private_key !== "string" || typeof account.private_key_id !== "string") throw Error("invalid_auth_service");
  const algorithm = { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" };
  const der = Buffer.from(account.private_key.replace(/-----[^-]+-----|\s/g, ""), "base64");
  const key = await crypto.subtle.importKey("pkcs8", der, algorithm, false, ["sign"]);
  const header = { alg: "RS256", typ: "JWT", kid: account.private_key_id };
  const claims = {
    iss: account.client_email, aud: oauthUrl,
    scope: "https://www.googleapis.com/auth/identitytoolkit",
    iat: now, exp: now + 300,
  };
  const assertion = base64url(JSON.stringify(header)) + "." + base64url(JSON.stringify(claims));
  const signature = await crypto.subtle.sign(algorithm, key, Buffer.from(assertion));
  const response = await fetchImpl(oauthUrl, {
    method: "POST", redirect: "manual", signal: AbortSignal.timeout(6_000),
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: assertion + "." + base64url(signature) }),
  });
  if (!response.ok) throw Error("auth_service_unavailable");
  const result = await response.json();
  if (typeof result.access_token !== "string" || !Number.isFinite(result.expires_in)) throw Error("invalid_auth_token");
  cached = { secret, token: result.access_token, expiresAt: now + result.expires_in };
  return cached.token;
}

export async function firebaseResetAction({ env, email }, fetchImpl = fetch) {
  const token = await accessToken(env.FIREBASE_AUTH_SERVICE_ACCOUNT, fetchImpl);
  const response = await fetchImpl("https://identitytoolkit.googleapis.com/v1/projects/" + PROJECT + "/accounts:sendOobCode", {
    method: "POST", redirect: "manual", signal: AbortSignal.timeout(6_000),
    headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" },
    body: JSON.stringify({ requestType: "PASSWORD_RESET", email, returnOobLink: true }),
  });
  if (!response.ok) throw Error("reset_unavailable");
  const result = await response.json();
  const link = new URL(result.oobLink);
  const code = link.searchParams.get("oobCode");
  if (link.protocol !== "https:" || link.searchParams.get("mode") !== "resetPassword" || !code) throw Error("invalid_reset_action");
  return code;
}
