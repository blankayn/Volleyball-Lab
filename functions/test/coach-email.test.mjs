import test from "node:test";
import assert from "node:assert/strict";
import { deadlineIsDue, emailDeliveryId, emailMessage, sendResend, submissionEmailCategory } from "../coach-email-policy.mjs";

test("email opt-in and category switches govern shared-session email", () => {
  assert.equal(submissionEmailCategory(undefined, {}), null);
  assert.equal(submissionEmailCategory({ emailNotifications: false, submissionAlerts: true }, {}), null);
  assert.equal(submissionEmailCategory({ emailNotifications: true, submissionAlerts: true }, {}), "submission");
  assert.equal(submissionEmailCategory({ emailNotifications: true, submissionAlerts: false, trainingNotifications: true }, {}), "training");
  assert.equal(submissionEmailCategory({ emailNotifications: true, submissionAlerts: false, trainingNotifications: false }, {}), null);
  assert.equal(submissionEmailCategory({ emailNotifications: true, trainingNotifications: false }, { notifySubmissions: false }), null);
});

test("deadline reminders exclude completed, missing, expired and distant dates", () => {
  const now = 1_800_000_000_000;
  assert.equal(deadlineIsDue({ dueAt: now + 86_400_000, status: "Pending" }, now), true);
  for (const assignment of [{ dueAt: now + 86_400_001 }, { dueAt: now }, { dueAt: null }, { dueAt: "tomorrow" }, { dueAt: now + 1000, status: "Completed" }]) assert.equal(deadlineIsDue(assignment, now), false);
});

test("mail escapes player content, stays on configured portal and has preference link", () => {
  const message = emailMessage({ category: "deadline", playerName: '<script>alert(1)</script>', assignmentTitle: 'Serve & receive', dueAt: 1_800_000_000_000, portalUrl: "https://volleyball.example/" });
  assert.ok(message.html.includes("&lt;script&gt;"));
  assert.ok(!message.html.includes("<script>"));
  assert.ok(message.html.includes("Serve &amp; receive"));
  assert.ok(message.text.includes("https://volleyball.example/#/app/assignments"));
  assert.ok(message.text.includes("https://volleyball.example/#/app/account"));
  assert.throws(() => emailMessage({ category: "submission", portalUrl: "javascript:alert(1)" }), /invalid_portal_url/);
});

test("Resend requests keep API key on server and stable event idempotency", async () => {
  const id = emailDeliveryId("coach", "submission/one/1");
  assert.equal(id, emailDeliveryId("coach", "submission/one/1"));
  assert.notEqual(id, emailDeliveryId("another-coach", "submission/one/1"));
  let request;
  const result = await sendResend({ apiKey: "test-server-only", from: "Coach <alerts@example.com>", to: "coach@example.com", id, message: { subject: "Session", text: "Open dashboard" }, fetchImpl: async (url, init) => { request = { url, ...init }; return { ok: true, json: async () => ({ id: "email-123" }) }; } });
  assert.equal(result, "email-123");
  assert.equal(request.url, "https://api.resend.com/emails");
  assert.equal(request.headers["Idempotency-Key"], `coach-alert/${id}`);
  const body = JSON.parse(request.body);
  assert.deepEqual(body.to, ["coach@example.com"]);
  assert.equal(body.apiKey, undefined);
});

test("provider failure never reports sent; invalid sender never calls provider", async () => {
  const args = { apiKey: "test", from: "alerts@example.com", to: "coach@example.com", id: "event", message: { text: "Review" } };
  await assert.rejects(sendResend({ ...args, fetchImpl: async () => ({ ok: false, status: 429 }) }), /resend_delivery_failed_429/);
  await assert.rejects(sendResend({ ...args, fetchImpl: async () => ({ ok: true, json: async () => ({}) }) }), /resend_invalid_response/);
  await assert.rejects(sendResend({ ...args, from: "", fetchImpl: () => { throw Error("must not run"); } }), /not_configured/);
});
