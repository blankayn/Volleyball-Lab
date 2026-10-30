import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { transform } from "esbuild";

const source = await readFile(new URL("../src/lib/coaching-unread.ts", import.meta.url), "utf8");
const { code } = await transform(source, { loader: "ts", format: "esm" });
const { unreadCount, playerUnreadCount, messageBadge } = await import(`data:text/javascript;base64,${Buffer.from(code).toString("base64")}`);

test("only incoming messages newer than the coach read receipt count as unread", () => {
  const thread = { playerUid: "a", createdAt: 100, coachReadAt: 100,
    followUp1: { createdAt: 200 }, followUp2: { createdAt: 300 } };
  assert.equal(unreadCount(thread), 2);
  assert.equal(unreadCount({ ...thread, coachReadAt: 200 }), 1);
  assert.equal(unreadCount({ ...thread, coachReadAt: 300 }), 0);
  assert.equal(unreadCount({ ...thread, followUp2: null, coachReadAt: 200 }), 0);
});

test("roster counts stay scoped to the student and include unread resolved history", () => {
  const questions = [
    { playerUid: "a", createdAt: 100, resolvedAt: 200 },
    { playerUid: "a", createdAt: 300, coachReadAt: 300 },
    { playerUid: "b", createdAt: 400 },
  ];
  assert.equal(playerUnreadCount(questions, "a"), 1);
  assert.equal(playerUnreadCount(questions, "b"), 1);
  assert.equal(playerUnreadCount(questions, "c"), 0);
  assert.equal(playerUnreadCount(null, "a"), 0);
});

test("no sample badge is shown when there are no unread messages", () => {
  assert.equal(messageBadge(0), "");
  assert.match(messageBadge(3), /3 unread messages/);
  assert.match(messageBadge(100), />99\+</);
});
