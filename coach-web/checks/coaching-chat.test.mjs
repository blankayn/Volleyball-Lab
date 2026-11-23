import test, { before, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

let chat, hub;
const state = () => globalThis.__coachChat;
before(async () => {
  const load = async (path) => {
    const result = await build({ entryPoints: [fileURLToPath(new URL(path, import.meta.url))], bundle: true, platform: "node", format: "cjs", write: false,
      plugins: [{ name: "boundary", setup(builder) {
        builder.onResolve({ filter: /^firebase\/firestore$|^\.\/firebase$/ }, args => ({ path: args.path, namespace: "fixture" }));
        builder.onLoad({ filter: /.*/, namespace: "fixture" }, () => ({ loader: "js", contents: `
          const s = () => globalThis.__coachChat;
          export const db = {}; export const collection = (_d, name) => ({ name }); export const query = (c) => c; export const where = () => {};
          export const doc = (a, ...p) => p.length ? { path: p.join("/"), id: p.at(-1) } : { path: a.name + "/new", id: "new" };
          export const serverTimestamp = () => "now"; export const Timestamp = { fromMillis: (ms) => ms }; export const runTransaction = async () => {};
          export const getDocs = async () => ({ docs: [] });
          export const getDoc = async (ref) => ({ exists: () => true, data: () => s().questions[ref.id] });
          export const setDoc = async (ref, data) => { s().writes.push([ref.path, data]); };
          export const writeBatch = () => ({ set: (r, d) => s().writes.push([r.path, d]), update: (r, d) => s().writes.push([r.path, d]), commit: async () => {} });` }));
      } }] });
    const module = { exports: {} };
    new Function("module", "exports", "require", result.outputFiles[0].text)(module, module.exports, createRequire(import.meta.url));
    return module.exports;
  };
  chat = await load("../src/lib/coaching-chat.ts");
  hub = await load("../src/lib/coaching-hub.ts");
});
beforeEach(() => { globalThis.__coachChat = { writes: [], questions: {} }; });
const question = (patch = {}) => ({ id: "q", coachUid: "coach", playerUid: "p", sessionId: null, topic: "Technique", text: "How low?", createdAt: 100, answer: null, answeredAt: null, ...patch });
const data = (messages = [], read = 0) => ({ messages, readAt: new Map([["p", read]]) });
const message = (at, fromCoach = false, text = "Hi", playerUid = "p") => ({ id: `m${at}`, playerUid, fromCoach, text, at });
const member = (status = "Active") => ({ playerUid: "p", playerName: "Mika <b>", status });

test("earlier questions and open messages share one timeline; a message still saving stays last", () => {
  const lines = chat.chatTimeline([question({ answer: "Bend more", answeredAt: 200 })], data([message(150), message(0, true, "Sending"), message(300, false, "Other", "x")]), "p");
  assert.deepEqual(lines.map(l => [l.text, l.fromCoach]), [["How low?", false], ["Hi", false], ["Bend more", true], ["Sending", true]]);
});
test("unread counts only that player's newer messages", () => {
  assert.equal(chat.chatUnread(data([message(100), message(200), message(300, true), message(400, false, "x", "other")], 100), "p"), 1);
  assert.equal(chat.chatUnread(null, "p"), 0);
});
test("a coach can start a chat, and a disconnected player's chat is read only", () => {
  const open = chat.renderChat("coach", member(), [], data());
  assert.match(open, /data-chat-form/); assert.match(open, /No messages yet/);
  assert.doesNotMatch(open, /Mika <b>/);
  assert.doesNotMatch(chat.renderChat("coach", member("Ended"), [], data([message(1)])), /data-chat-form/);
  assert.match(chat.renderChat("coach", member(), [], null), /could not load/);
});
test("message text is escaped", () => {
  assert.doesNotMatch(chat.renderChat("coach", member(), [], data([message(1, false, "<img src=x>")])), /<img src=x>/);
});
test("a waiting question is answered first; otherwise the message goes to open chat", async () => {
  state().questions.q = { coachUid: "coach", createdAt: 100, answer: null };
  await chat.sendChat("coach", "p", "Bend more", [question()]);
  assert.deepEqual(state().writes.map(w => w[0]), ["coachingQuestions/q", "coachResponseTimes/q"]);
  state().writes = [];
  await chat.sendChat("coach", "p", "Great work", [question({ answer: "Done", answeredAt: 150 }), question({ id: "z", playerUid: "other" })]);
  assert.deepEqual(state().writes, [["coachingMessages/new", { id: "new", coachUid: "coach", playerUid: "p", senderUid: "coach", text: "Great work", createdAt: "now" }]]);
});
test("the website refuses the same contact details as the rules", () => {
  for (const text of ["Call 0917 123 4567", "a@b.com", "DM @player1", "coach.ph", "https://x.io", "WhatsApp me"]) assert.ok(hub.contactText.test(text), text);
  for (const text of ["3 sets of 10 reps", "Hold for 30 seconds"]) assert.ok(!hub.contactText.test(text), text);
});
