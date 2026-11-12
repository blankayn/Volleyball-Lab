import { createHmac, randomInt, randomUUID } from "node:crypto";
import { Buffer } from "node:buffer";

const LIFETIME_MS = 10 * 60_000;
const HOUR_MS = 60 * 60_000;
const hash = (secret, value) => createHmac("sha256", secret).update(value).digest("hex");

class RecoveryError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

async function bodyData(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get("content-type") ?? "")) {
    throw new RecoveryError("INVALID_ARGUMENT", "Send a JSON request.");
  }
  const reader = request.body?.getReader();
  if (!reader) throw new RecoveryError("INVALID_ARGUMENT", "Enter your recovery details.");
  const chunks = [];
  let size = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > 2_048) {
      await reader.cancel();
      throw new RecoveryError("INVALID_ARGUMENT", "The request is too large.");
    }
    chunks.push(value);
  }
  try {
    const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!body || Object.keys(body).join() !== "data" || !body.data || Array.isArray(body.data)) throw Error();
    return body.data;
  } catch {
    throw new RecoveryError("INVALID_ARGUMENT", "Enter your recovery details.");
  }
}

export function createRecoveryWorker({ sendCode, resetAction, now = Date.now } = {}) {
  return {
    async fetch(request, env) {
      const headers = { "Cache-Control": "no-store", "Content-Type": "application/json", "X-Content-Type-Options": "nosniff" };
      const path = new URL(request.url).pathname;
      if (!["/sendCode", "/verifyCode"].includes(path)) return Response.json({ error: { status: "NOT_FOUND" } }, { status: 404, headers });
      if (request.method !== "POST") return Response.json({ error: { status: "INVALID_ARGUMENT" } }, { status: 405, headers: { ...headers, Allow: "POST" } });
      try {
        if (!env.AUTH_DB || !env.RECOVERY_SECRET || !env.FIREBASE_AUTH_SERVICE_ACCOUNT ||
            !env.GMAIL_APP_PASSWORD || !sendCode || !resetAction) {
          throw new RecoveryError("UNAVAILABLE", "Password recovery is being configured.");
        }
        const data = await bodyData(request);
        const time = now();
        const secret = env.RECOVERY_SECRET;
        if (path === "/sendCode") {
          if (Object.keys(data).join() !== "email" || typeof data.email !== "string") {
            throw new RecoveryError("INVALID_ARGUMENT", "Enter your email address.");
          }
          const email = data.email.trim().toLowerCase();
          if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
            throw new RecoveryError("INVALID_ARGUMENT", "Enter your email address.");
          }
          const ip = request.headers.get("CF-Connecting-IP");
          if (!ip) throw new RecoveryError("UNAVAILABLE", "Please try again shortly.");
          // Hourly counters are claimed atomically, including concurrent requests.
          const counters = await env.AUTH_DB.batch([
            ["email", email, HOUR_MS], ["ip", ip, HOUR_MS], ["global", "daily", 24 * HOUR_MS],
          ].map(([kind, value, window]) => env.AUTH_DB.prepare(
            "INSERT INTO recovery_rate_limits(id, requests, expires_at) VALUES (?, 1, ?) " +
            "ON CONFLICT(id) DO UPDATE SET requests = requests + 1 RETURNING requests",
          ).bind(hash(secret, kind + ":" + value + ":" + Math.floor(time / window)), time + window)));
          if (counters[0].results[0].requests > 5 || counters[1].results[0].requests > 10 || counters[2].results[0].requests > 100) {
            throw new RecoveryError("RESOURCE_EXHAUSTED", "Too many requests. Please try again later.");
          }
          const id = randomUUID();
          const code = randomInt(0, 1_000_000).toString().padStart(6, "0");
          await env.AUTH_DB.prepare(
            "INSERT INTO password_recovery(id, email, code_hash, expires_at) VALUES (?, ?, ?, ?)",
          ).bind(id, email, hash(secret, id + ":" + code), time + LIFETIME_MS).run();
          try {
            await sendCode({ env, email, code });
          } catch {
            await env.AUTH_DB.prepare("DELETE FROM password_recovery WHERE id = ?").bind(id).run();
            throw new RecoveryError("UNAVAILABLE", "Could not send the code. Please try again.");
          }
          return Response.json({ result: { challengeId: id, resendAfterSeconds: 60 } }, { headers });
        }
        if (Object.keys(data).sort().join() !== "challengeId,code" ||
            typeof data.challengeId !== "string" || !/^[a-f0-9-]{36}$/.test(data.challengeId) ||
            typeof data.code !== "string" || !/^\d{6}$/.test(data.code)) {
          throw new RecoveryError("INVALID_ARGUMENT", "Enter the six-digit code from your email.");
        }
        // Consume a correct code in the same write that counts the attempt.
        // A second request cannot redeem the same code, even concurrently.
        const row = await env.AUTH_DB.prepare(
          "UPDATE password_recovery SET attempts = attempts + 1, " +
          "used_at = CASE WHEN code_hash = ? THEN ? ELSE NULL END " +
          "WHERE id = ? AND used_at IS NULL AND expires_at > ? AND attempts < 5 " +
          "RETURNING email, used_at",
        ).bind(hash(secret, data.challengeId + ":" + data.code), time, data.challengeId, time).first();
        if (!row || row.used_at === null) {
          throw new RecoveryError("INVALID_ARGUMENT", "This code is invalid, expired, or already used. Request another code.");
        }
        const actionCode = await resetAction({ env, email: row.email });
        return Response.json({ result: { actionCode, email: row.email } }, { headers });
      } catch (error) {
        const known = error instanceof RecoveryError;
        const status = known ? error.status : "UNAVAILABLE";
        const http = { INVALID_ARGUMENT: 400, RESOURCE_EXHAUSTED: 429, UNAVAILABLE: 503 }[status] ?? 503;
        return Response.json({ error: { status, message: known ? error.message : "Could not verify the code. Request another code and try again." } }, { status: http, headers });
      }
    },
    async scheduled(_event, env) {
      await env.AUTH_DB.batch([
        env.AUTH_DB.prepare("DELETE FROM password_recovery WHERE expires_at <= ?").bind(now()),
        env.AUTH_DB.prepare("DELETE FROM recovery_rate_limits WHERE expires_at <= ?").bind(now()),
      ]);
    },
  };
}
