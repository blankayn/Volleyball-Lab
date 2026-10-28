import test from "node:test";
import assert from "node:assert/strict";
import { DEFAULT_GROQ_MODEL, requestGroqExplanation, sessionEvidence } from "../../functions/session-explanation-policy.mjs";

const evidence = sessionEvidence({ skill: "Serving", repCount: 3, reps: Array.from({ length: 3 }, () => ({ cueScores: [
  { cueId: "ServeTrunkLean", measured: 9, score: 1 },
  { cueId: "ServeContactArmExtension", measured: 116, score: .3 },
] })) });
const explanation = {
  overview: "Use the recorded posture to guide your next practice.",
  strengths: [{ cueId: "ServeTrunkLean", explanation: "The trunk position is within the app reference." }],
  priorities: [{ cueId: "ServeContactArmExtension", explanation: "The hitting arm is below the app reference. Practise meeting the ball with a straight arm." }],
};
const success = value => Response.json({ choices: [{ finish_reason: "stop", message: { content: JSON.stringify(value) } }] });

test("Groq request keeps credentials in the header and restricts output to selected cues", async () => {
  let request;
  const result = await requestGroqExplanation({ apiKey: "server-only-test-key", evidence, fetchImpl: async (url, init) => {
    request = { url, ...init }; return success(explanation);
  } });
  assert.equal(request.url, "https://api.groq.com/openai/v1/chat/completions");
  assert.equal(request.headers.Authorization, "Bearer server-only-test-key");
  const payload = JSON.parse(request.body);
  assert.equal(payload.model, DEFAULT_GROQ_MODEL);
  assert.equal(payload.response_format.json_schema.strict, true);
  const schema = payload.response_format.json_schema.schema;
  assert.equal(schema.additionalProperties, false);
  assert.deepEqual(schema.properties.priorities.items.properties.cueId.enum, [...evidence.strengths, ...evidence.priorities]);
  const facts = JSON.parse(payload.messages[1].content);
  assert.deepEqual(facts.priorities, evidence.priorities);
  assert.ok(!/server-only-test-key|ownerUid|sessionId|measuredReps|formScore/.test(payload.messages[1].content));
  assert.ok(!/\p{N}/u.test(payload.messages[1].content));
  assert.deepEqual(result, explanation);
});

test("Groq throttling never retries and HTTP-success error bodies are rejected", async () => {
  let calls = 0;
  await assert.rejects(requestGroqExplanation({ apiKey: "test", evidence, fetchImpl: async () => { calls++; return new Response(null, { status: 429 }); } }), /groq_rate_limited/);
  assert.equal(calls, 1);
  await assert.rejects(requestGroqExplanation({ apiKey: "test", evidence, fetchImpl: async () => Response.json({ error: { code: "rate_limit_exceeded" } }) }), /groq_rate_limited/);
});

test("Groq retries one server failure inside the original deadline", async () => {
  const signals = [];
  const result = await requestGroqExplanation({ apiKey: "test", evidence, fetchImpl: async (_url, init) => {
    signals.push(init.signal); return signals.length === 1 ? new Response(null, { status: 503 }) : success(explanation);
  } });
  assert.deepEqual(result, explanation);
  assert.equal(signals.length, 2);
  assert.equal(signals[0], signals[1]);
});

test("Groq output still passes the grounding validator and must be complete", async () => {
  await assert.rejects(requestGroqExplanation({ apiKey: "test", evidence, fetchImpl: async () => success({ ...explanation, overview: "Your score is 99." }) }), /ungrounded_explanation/);
  await assert.rejects(requestGroqExplanation({ apiKey: "test", evidence, fetchImpl: async () => Response.json({ choices: [{ finish_reason: "length", message: { content: "{}" } }] }) }), /incomplete_explanation/);
});
