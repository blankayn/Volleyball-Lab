import test, { before, after, beforeEach } from "node:test";
import { readFile } from "node:fs/promises";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, query, setDoc, updateDoc, where, writeBatch } from "firebase/firestore";

let env;
const now = Date.now();
const photo = "data:image/jpeg;base64,/9j/4AAQSkZJRg==";
before(async () => {
  env = await initializeTestEnvironment({ projectId: "demo-note-media", firestore: { rules: await readFile(new URL("../../firestore.rules", import.meta.url), "utf8") } });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await reset(); });

const member = status => ({ playerUid: "player", playerName: "Athlete", position: null, status, requestedAt: now, decidedAt: null });
async function reset(status = "Active") { await env.clearFirestore(); await seed("roster/coach/members/player", member(status)); await seed("entitlements/coach", { status: "active", expiresAtEpochMs: 0 }); }
async function seed(path, data) { await env.withSecurityRulesDisabled(async context => setDoc(doc(context.firestore(), path), data)); }
const client = uid => env.authenticatedContext(uid).firestore();
const note = (extra = {}) => ({ id: "n", coachUid: "coach", playerUid: "player", sessionId: "s", text: "Straighter arms.", createdAt: now, ...extra });
const media = (extra = {}) => ({ coachUid: "coach", playerUid: "player", photo, createdAt: now, ...extra });
function send(uid, noteData = note({ hasPhoto: true }), mediaData = media()) {
  const db = client(uid);
  const batch = writeBatch(db);
  if (noteData) batch.set(doc(db, "notes/n"), noteData);
  if (mediaData) batch.set(doc(db, "noteMedia/n"), mediaData);
  return batch.commit();
}

test("plain notes keep working exactly as before", async () => {
  await assertSucceeds(send("coach", note({ sessionId: null }), null));
});

test("a moment needs its session and sane values; a rep number needs a moment", async () => {
  await assertSucceeds(send("coach", note({ videoMs: 12_000, repNumber: 4 }), null));
  for (const bad of [{ videoMs: -1 }, { videoMs: 3_600_001 }, { videoMs: 1.5 }, { videoMs: "12" }, { repNumber: 4 }, { videoMs: 1, repNumber: 0 },
    { videoMs: 1, repNumber: 1001 }, { videoMs: 1, sessionId: null }, { extra: true }, { hasPhoto: false }]) {
    await reset();
    await assertFails(send("coach", note(bad), null));
  }
});

test("photos in feedback are part of Pro, written feedback is not", async () => {
  await env.withSecurityRulesDisabled(async context => setDoc(doc(context.firestore(), "entitlements/coach"), { status: "active", expiresAtEpochMs: 1 }));
  const coach = client("coach"), batch = writeBatch(coach);
  batch.set(doc(coach, "notes/n"), note({ hasPhoto: true }));
  batch.set(doc(coach, "noteMedia/n"), media());
  await assertFails(batch.commit());
  await assertSucceeds(setDoc(doc(coach, "notes/n"), note({ sessionId: null })));
});
test("a photo and its note commit together or not at all", async () => {
  await assertSucceeds(send("coach"));
  await reset();
  await assertFails(send("coach", note({ hasPhoto: true }), null));
  await assertFails(send("coach", null, media()));
  await assertFails(send("coach", note(), media()));
  await assertFails(send("coach", note({ hasPhoto: true }), media({ playerUid: "other" })));
});

test("only the note's coach and player can open the photo", async () => {
  await assertSucceeds(send("coach"));
  await assertSucceeds(getDoc(doc(client("coach"), "noteMedia/n")));
  await assertSucceeds(getDoc(doc(client("player"), "noteMedia/n")));
  await assertFails(getDoc(doc(client("other"), "noteMedia/n")));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "noteMedia/n")));
  await assertFails(getDocs(query(collection(client("player"), "noteMedia"), where("playerUid", "==", "player"))));
});

test("only an active coach may attach, and only as themselves", async () => {
  await assertFails(send("other"));
  await assertFails(send("player"));
  await reset("Ended");
  await assertFails(send("coach"));
  await reset("Pending");
  await assertFails(send("coach"));
});

test("only a bounded JPEG is accepted", async () => {
  for (const bad of ["", "https://example.com/a.jpg", "data:image/png;base64,AAAA", "data:image/svg+xml;base64,AAAA", "data:text/html;base64,AAAA",
    "data:image/jpeg;base64,AA\"><script>", "data:image/jpeg;base64," + "A".repeat(300_000), 5]) {
    await assertFails(send("coach", note({ hasPhoto: true }), media({ photo: bad })));
  }
  await assertFails(send("coach", note({ hasPhoto: true }), media({ caption: "extra" })));
  await assertSucceeds(send("coach", note({ hasPhoto: true }), media({ photo: "data:image/jpeg;base64," + "A".repeat(299_000) })));
});

test("a photo cannot be swapped or added later; the coach can remove it and the words stay", async () => {
  await assertSucceeds(send("coach"));
  await assertFails(updateDoc(doc(client("coach"), "noteMedia/n"), { createdAt: now + 1 }));
  await assertFails(deleteDoc(doc(client("player"), "noteMedia/n")));
  await assertFails(deleteDoc(doc(client("other"), "noteMedia/n")));
  await assertSucceeds(deleteDoc(doc(client("coach"), "noteMedia/n")));
  await assertSucceeds(getDoc(doc(client("player"), "noteMedia/n")));
  await assertSucceeds(getDoc(doc(client("player"), "notes/n")));
  await assertFails(setDoc(doc(client("coach"), "noteMedia/n"), media()));
});

test("account deletion blocks new photos and reads", async () => {
  await seed("accountDeletions/player", { status: "deleting" });
  await assertFails(send("coach"));
  await reset();
  await assertSucceeds(send("coach"));
  await seed("accountDeletions/player", { status: "deleting" });
  await assertFails(getDoc(doc(client("player"), "noteMedia/n")));
});
