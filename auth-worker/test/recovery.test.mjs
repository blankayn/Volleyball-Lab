import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { createRecoveryWorker } from "../src/recovery.mjs";

function fixture() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(readFileSync(new URL("../migrations/0001_password_recovery.sql", import.meta.url), "utf8"));
  const db = {
    prepare(sql) {
      let args = [];
      return {
        bind(...values) { args = values; return this; },
        async run() { return sqlite.prepare(sql).run(...args); },
        async first() { return sqlite.prepare(sql).get(...args) ?? null; },
        execute() {
          const statement = sqlite.prepare(sql);
          return /RETURNING/.test(sql) ? { results: statement.all(...args) } : statement.run(...args);
        },
      };
    },
    async batch(statements) {
      sqlite.exec("BEGIN");
      try {
        const results = statements.map(statement => statement.execute());
        sqlite.exec("COMMIT");
        return results;
      } catch (error) { sqlite.exec("ROLLBACK"); throw error; }
    },
  };
  let time = Date.UTC(2026, 10, 12);
  let failSend = false;
  const deliveries = [];
  const resets = [];
  const env = { AUTH_DB: db, RECOVERY_SECRET: "test-secret", FIREBASE_AUTH_SERVICE_ACCOUNT: "test-only", GMAIL_APP_PASSWORD: "test-only" };
  const worker = createRecoveryWorker({
    now: () => time,
    async sendCode(message) {
      if (failSend) throw Error("private-provider-details");
      deliveries.push(message);
    },
    async resetAction({ email }) { resets.push(email); return "firebase-action-code"; },
  });
  async function call(path, data, ip = "192.0.2.1") {
    const response = await worker.fetch(new Request("https://test.example/" + path, {
      method: "POST", headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
      body: JSON.stringify({ data }),
    }), env);
    return { status: response.status, ...(await response.json()) };
  }
  return { call, deliveries, resets, sqlite, env, worker,
    advance(ms) { time += ms; }, failDelivery() { failSend = true; } };
}

test("emailed code verifies before returning a Firebase reset action and cannot be reused", async () => {
  const f = fixture();
  const sent = await f.call("sendCode", { email: " Athlete@Example.com " });
  assert.equal(sent.status, 200);
  assert.deepEqual(Object.keys(sent.result).sort(), ["challengeId", "resendAfterSeconds"]);
  assert.equal(f.deliveries[0].email, "athlete@example.com");
  assert.match(f.deliveries[0].code, /^\d{6}$/);
  assert.equal(f.resets.length, 0);
  const stored = f.sqlite.prepare("SELECT * FROM password_recovery").get();
  assert.notEqual(stored.code_hash, f.deliveries[0].code);
  const data = { challengeId: sent.result.challengeId, code: f.deliveries[0].code };
  const result = await f.call("verifyCode", data);
  assert.deepEqual(result.result, { email: "athlete@example.com", actionCode: "firebase-action-code" });
  assert.equal((await f.call("verifyCode", data)).status, 400);
  assert.deepEqual(f.resets, ["athlete@example.com"]);
});

test("wrong codes exhaust five attempts and expired codes are rejected", async () => {
  const f = fixture();
  const sent = await f.call("sendCode", { email: "athlete@example.com" });
  const correct = f.deliveries[0].code;
  const wrong = correct === "000000" ? "111111" : "000000";
  for (let attempt = 0; attempt < 5; attempt++) {
    assert.equal((await f.call("verifyCode", { challengeId: sent.result.challengeId, code: wrong })).status, 400);
  }
  assert.equal((await f.call("verifyCode", { challengeId: sent.result.challengeId, code: correct })).status, 400);
  const second = await f.call("sendCode", { email: "athlete@example.com" });
  f.advance(600_000);
  assert.equal((await f.call("verifyCode", { challengeId: second.result.challengeId, code: f.deliveries[1].code })).status, 400);
  assert.equal(f.resets.length, 0);
});

test("concurrent verification redeems a code once", async () => {
  const f = fixture();
  const sent = await f.call("sendCode", { email: "athlete@example.com" });
  const data = { challengeId: sent.result.challengeId, code: f.deliveries[0].code };
  const results = await Promise.all([f.call("verifyCode", data), f.call("verifyCode", data)]);
  assert.deepEqual(results.map(result => result.status).sort(), [200, 400]);
  assert.equal(f.resets.length, 1);
});

test("email and network rate limits hold across concurrent requests", async () => {
  const f = fixture();
  for (let i = 0; i < 5; i++) assert.equal((await f.call("sendCode", { email: "athlete@example.com" })).status, 200);
  assert.equal((await f.call("sendCode", { email: "ATHLETE@example.com" }, "192.0.2.2")).status, 429);
  for (let i = 0; i < 5; i++) assert.equal((await f.call("sendCode", { email: "other" + i + "@example.com" })).status, 200);
  assert.equal((await f.call("sendCode", { email: "last@example.com" })).status, 429);
  f.advance(3_600_000);
  assert.equal((await f.call("sendCode", { email: "athlete@example.com" })).status, 200);
});

test("failed delivery removes the code and exposes no provider details", async () => {
  const f = fixture();
  f.failDelivery();
  const result = await f.call("sendCode", { email: "athlete@example.com" });
  assert.equal(result.status, 503);
  assert.equal(JSON.stringify(result).includes("private-provider"), false);
  assert.equal(f.sqlite.prepare("SELECT count(*) AS total FROM password_recovery").get().total, 0);
});

test("malformed input is rejected and scheduled cleanup removes expired email data", async () => {
  const f = fixture();
  for (const email of ["not-an-email", "a@example.com\r\nbcc:other@example.com", "x".repeat(255) + "@example.com"]) {
    assert.equal((await f.call("sendCode", { email })).status, 400);
  }
  const sent = await f.call("sendCode", { email: "athlete@example.com" });
  assert.equal((await f.call("verifyCode", { challengeId: sent.result.challengeId, code: "12345" })).status, 400);
  f.advance(86_400_001);
  await f.worker.scheduled({}, f.env);
  assert.equal(f.sqlite.prepare("SELECT count(*) AS total FROM password_recovery").get().total, 0);
  assert.equal(f.sqlite.prepare("SELECT count(*) AS total FROM recovery_rate_limits").get().total, 0);
});
