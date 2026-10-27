import test, { before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc, updateDoc } from "firebase/firestore";

let env;
before(async () => {
  env = await initializeTestEnvironment({ projectId: "demo-volleyball-session-ai", firestore: {
    rules: await readFile(new URL("../../firestore.rules", import.meta.url), "utf8"),
  } });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    const rows = {
      "sessions/session": { ownerUid: "player", sharedWith: ["coach", "ended", "pending", "fake-coach"] },
      "users/coach": { role: "COACH" }, "users/ended": { role: "COACH" },
      "users/pending": { role: "COACH" }, "users/fake-coach": { role: "PLAYER" },
      "users/unshared": { role: "COACH" },
      "roster/coach/members/player": { status: "Active", playerUid: "player" },
      "roster/ended/members/player": { status: "Ended", playerUid: "player" },
      "roster/pending/members/player": { status: "Pending", playerUid: "player" },
      "roster/fake-coach/members/player": { status: "Active", playerUid: "player" },
      "roster/unshared/members/player": { status: "Active", playerUid: "player" },
    };
    await Promise.all(Object.entries(rows).map(([path, value]) => setDoc(doc(db, path), value)));
  });
});

const client = uid => env.authenticatedContext(uid).firestore();
const gate = (uid, sessionId = "session") => doc(client(uid), `aiSessionAccess/${sessionId}`);
const seed = async (path, value) => env.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), path), value));

test("the session owner and active shared coach can probe a nonexistent AI gate", async () => {
  for (const uid of ["player", "coach"]) {
    const result = await assertSucceeds(getDoc(gate(uid)));
    assert.equal(result.exists(), false);
  }
});

test("ended, pending, unshared and non-coach accounts cannot use the AI gate", async () => {
  for (const uid of ["ended", "pending", "unshared", "fake-coach", "stranger"])
    await assertFails(getDoc(gate(uid)));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "aiSessionAccess/session")));
});

test("missing sessions and caller or owner deletion fences close the gate", async () => {
  await assertFails(getDoc(gate("player", "missing")));
  await assertFails(getDoc(gate("coach", "missing")));
  await seed("accountDeletions/coach", { status: "deleting" });
  await assertFails(getDoc(gate("coach")));
  await assertSucceeds(getDoc(gate("player")));
  await seed("accountDeletions/player", { status: "deleting" });
  await assertFails(getDoc(gate("player")));
  await assertFails(getDoc(gate("unshared")));
});

test("an owner deletion fence denies a still-active shared coach", async () => {
  await seed("accountDeletions/player", { status: "deleted" });
  await assertFails(getDoc(gate("coach")));
});

test("the virtual gate cannot be listed or written by clients", async () => {
  const db = client("player");
  await assertFails(getDocs(collection(db, "aiSessionAccess")));
  await assertFails(setDoc(gate("player"), { allow: true }));
  await seed("aiSessionAccess/session", { accidental: "server-only fixture" });
  await assertFails(updateDoc(gate("player"), { allow: true }));
  await assertFails(deleteDoc(gate("player")));
});

test("AI gating does not change historical shared-session read access", async () => {
  await assertSucceeds(getDoc(doc(client("ended"), "sessions/session")));
  await assertFails(getDoc(gate("ended")));
});
