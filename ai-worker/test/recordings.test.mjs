import test from "node:test";
import assert from "node:assert/strict";
import { createWorker } from "../src/worker.mjs";
import { MAX_VIDEO_BYTES } from "../src/recordings.mjs";

const time = Date.UTC(2026, 10, 3);
const token = uid => {
  const part = value => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${part({ alg: "RS256", kid: "test-key" })}.${part({ sub: uid, aud: "volleyball-lab", iss: "https://securetoken.google.com/volleyball-lab", iat: time / 1000 - 1, exp: time / 1000 + 3600 })}.test-signature`;
};

function fixture() {
  const objects = new Map(); let gated = true; let sourceExists = true; let reads = 0;
  const bucket = {
    async head(key) { const bytes = objects.get(key); return bytes ? { size: bytes.byteLength } : null; },
    async get(key) { const bytes = objects.get(key); return bytes ? { size: bytes.byteLength, body: bytes } : null; },
    async put(key, body, options = {}) {
      // Deliberately yield before conditional insert to exercise concurrent retries.
      const bytes = new Uint8Array(await new Response(body).arrayBuffer());
      if (options.onlyIf?.etagDoesNotMatch === "*" && objects.has(key)) return null;
      objects.set(key, bytes); return { size: bytes.byteLength };
    },
  };
  const worker = createWorker({ now: () => time, fetchImpl: async (url, init) => {
    reads++;
    assert.equal(new URL(url).hostname, "firestore.googleapis.com");
    assert.equal(init.redirect, "manual");
    const uid = ["player", "coach", "other"].find(uid => init.headers.Authorization === `Bearer ${token(uid)}`);
    if (!uid) return Response.json({ error: { status: "UNAUTHENTICATED" } }, { status: 401 });
    if (url.includes("/aiSessionAccess/")) return Response.json({ error: { status: gated && uid !== "other" ? "NOT_FOUND" : "PERMISSION_DENIED" } }, { status: gated && uid !== "other" ? 404 : 403 });
    if (!sourceExists) return Response.json({ error: { status: "NOT_FOUND" } }, { status: 404 });
    return Response.json({ fields: { ownerUid: { stringValue: "player" }, sharedWith: { arrayValue: { values: [{ stringValue: "coach" }] } } } });
  } });
  const env = { RECORDINGS: bucket };
  const call = (file = "video.mp4", method = "GET", uid = "player", data, extraHeaders = {}) => worker.fetch(new Request(`https://worker.example/recordings/session/${file}`, {
    method, headers: { ...(uid ? { Authorization: `Bearer ${token(uid)}` } : {}), ...(data !== undefined ? { "Content-Type": file === "video.mp4" ? "video/mp4" : "application/json", "Content-Length": String(Buffer.byteLength(data)) } : {}), ...extraHeaders },
    ...(data !== undefined ? { body: data } : {}),
  }), env);
  return { objects, env, call, reads: () => reads, gate: value => { gated = value; }, exists: value => { sourceExists = value; } };
}

test("both private objects must finish before owner or coach can play; retries preserve originals", async () => {
  const f = fixture();
  assert.equal((await f.call("complete", "POST")).status, 409);
  assert.equal((await f.call("video.mp4", "PUT", "player", "synthetic-video")).status, 200);
  assert.equal((await f.call()).status, 404);
  assert.equal((await f.call("complete", "POST")).status, 409);
  assert.equal((await f.call("review.json", "PUT", "player", '{"width":640,"height":480}')).status, 200);
  assert.equal((await f.call("complete", "POST")).status, 200);
  const video = await f.call("video.mp4", "GET", "coach");
  assert.equal(video.status, 200); assert.equal(await video.text(), "synthetic-video");
  assert.equal(video.headers.get("cache-control"), "private, no-store");
  assert.equal(video.headers.get("content-type"), "video/mp4");
  const head = await f.call("video.mp4", "HEAD", "coach");
  assert.equal(head.status, 200); assert.equal(await head.text(), "");
  assert.equal((await f.call("video.mp4", "PUT", "player", "synthetic-video")).status, 200);
  assert.equal((await f.call("video.mp4", "PUT", "player", "different-size")).status, 409);
  assert.equal(await (await f.call()).text(), "synthetic-video");
});

test("anonymous, unrelated, forged and revoked accounts cannot read or write R2", async () => {
  const f = fixture();
  assert.equal((await f.call("video.mp4", "PUT", null, "video")).status, 401);
  assert.equal(f.reads(), 0);
  assert.equal((await f.call("video.mp4", "PUT", "other", "video")).status, 403);
  assert.equal((await f.call("video.mp4", "PUT", "coach", "video")).status, 403);
  assert.equal((await f.call("complete", "POST", "coach")).status, 403);
  assert.equal((await f.call("video.mp4", "GET", "player", undefined, { Authorization: `Bearer ${token("forged")}` })).status, 401);
  f.gate(false);
  assert.equal((await f.call("video.mp4", "GET", "coach")).status, 403);
  assert.equal((await f.call("video.mp4", "HEAD", "coach")).status, 403);
  assert.equal((await f.call("video.mp4", "PUT", "player", "video")).status, 403);
  assert.equal(f.objects.size, 0);
});

test("deleted sessions, invalid paths, missing binding, types and oversize uploads fail closed", async () => {
  const f = fixture();
  f.exists(false); assert.equal((await f.call("video.mp4", "PUT", "player", "video")).status, 404);
  f.exists(true);
  assert.equal((await f.call("video.mp4", "PUT", "player", "video", { "Content-Length": String(MAX_VIDEO_BYTES + 1) })).status, 400);
  assert.equal((await f.call("video.mp4", "PUT", "player", "video", { "Content-Type": "text/html" })).status, 400);
  assert.equal((await f.call("video.mp4", "PUT", "player", "")).status, 400);
  assert.equal((await f.call("ready.json", "GET")).status, 404);
  assert.equal((await f.call("video.mp4", "DELETE")).status, 405);
  delete f.env.RECORDINGS;
  assert.equal((await f.call()).status, 503);
});

test("concurrent retries cannot overwrite a committed video", async () => {
  const f = fixture();
  const results = await Promise.all([f.call("video.mp4", "PUT", "player", "first"), f.call("video.mp4", "PUT", "player", "second")]);
  assert.deepEqual(results.map(response => response.status).sort(), [200, 409]);
  assert.equal(Buffer.from(f.objects.get("recordings/player/session/video.mp4")).toString(), "first");
});

test("recording CORS supports the coach portal and rejects third-party origins", async () => {
  const f = fixture();
  const allowed = await f.call("video.mp4", "OPTIONS", null, undefined, { Origin: "https://volleyball-lab.web.app" });
  assert.equal(allowed.status, 204); assert.ok(allowed.headers.get("access-control-allow-methods").includes("GET"));
  assert.equal((await f.call("video.mp4", "OPTIONS", null, undefined, { Origin: "https://example.invalid" })).status, 403);
});

