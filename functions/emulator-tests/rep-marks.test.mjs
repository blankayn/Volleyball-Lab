import test, { before, after, beforeEach } from "node:test";
import { readFile } from "node:fs/promises";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDocs, query, serverTimestamp, setDoc, Timestamp, where } from "firebase/firestore";
let env;
before(async () => { env = await initializeTestEnvironment({ projectId: "demo-volleyball-learning", firestore: { rules: await readFile(new URL("../../firestore.rules", import.meta.url), "utf8") } }); });
after(async () => { await env?.cleanup(); });
const admin = (work) => env.withSecurityRulesDisabled((context) => work(context.firestore()));
beforeEach(async () => {
  await env.clearFirestore();
  await admin(async (db) => {
    await setDoc(doc(db, "users/coach"), { role: "COACH" });
    await setDoc(doc(db, "entitlements/coach"), { status: "active", expiresAtEpochMs: 0 });
    await setDoc(doc(db, "coachDirectory/coach"), { name: "coach" });
    await setDoc(doc(db, "roster/coach/members/player"), { playerUid: "player", status: "Active" });
    await setDoc(doc(db, "sessions/s1"), { id: "s1", ownerUid: "player", skill: "Receiving", repCount: 3, sharedWith: ["coach"] });
  });
});
const db = (uid) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).firestore();
const label = (over = {}) => ({ sessionId: "s1", playerUid: "player", coachUid: "coach", skill: "Receiving", repNumber: 1,
  verdict: "Wrong", cueIds: ["PassKneeFlexion"], createdAt: serverTimestamp(), ...over });
const put = (data, uid = "coach", id = `${data.sessionId}_${data.repNumber}_${data.coachUid}`) => setDoc(doc(db(uid), `repLabels/${id}`), data);

test("a connected coach marks reps of a session sent to them, and can change or undo it", async () => {
  await assertSucceeds(put(label()));
  await assertSucceeds(put(label({ verdict: "Right", cueIds: [] })));
  await assertSucceeds(getDocs(query(collection(db("coach"), "repLabels"), where("coachUid", "==", "coach"), where("sessionId", "==", "s1"))));
  await assertSucceeds(getDocs(query(collection(db("player"), "repLabels"), where("playerUid", "==", "player"))));
  await assertFails(getDocs(query(collection(db("stranger"), "repLabels"), where("playerUid", "==", "player"))));
  await assertSucceeds(deleteDoc(doc(db("coach"), "repLabels/s1_1_coach")));
});
test("no connection or no access to the session means no mark", async () => {
  await admin((d) => setDoc(doc(d, "roster/coach/members/player"), { playerUid: "player", status: "Ended" }));
  await assertFails(put(label()));
  await admin((d) => setDoc(doc(d, "roster/coach/members/player"), { playerUid: "player", status: "Active" }));
  await admin((d) => setDoc(doc(d, "sessions/s1"), { id: "s1", ownerUid: "player", skill: "Receiving", repCount: 3, sharedWith: [] }));
  await assertFails(put(label()));
});
test("marking reps is part of Pro", async () => {
  await admin((d) => deleteDoc(doc(d, "entitlements/coach")));
  await assertFails(put(label()));
});
test("made-up reps, other people's marks and extra data are refused", async () => {
  for (const bad of [{ repNumber: 0 }, { repNumber: 4 }, { repNumber: 1.5 }, { skill: "Serving" }, { verdict: "Maybe" }, { cueIds: ["Anything"] },
    { cueIds: "PassKneeFlexion" }, { verdict: "Right" }, { note: "name" }, { createdAt: Timestamp.fromMillis(1) }, { playerUid: "other" }]) await assertFails(put(label(bad)));
  await assertFails(put(label(), "stranger"));
  await assertFails(put(label(), "player"));
  await assertFails(put(label(), "coach", "another-id"));
  await assertFails(put(label({ playerUid: "coach", coachUid: "coach" })));
});
test("the player deletes every mark about them, and nobody else can", async () => {
  await assertSucceeds(put(label()));
  await assertFails(deleteDoc(doc(db("stranger"), "repLabels/s1_1_coach")));
  await assertSucceeds(deleteDoc(doc(db("player"), "repLabels/s1_1_coach")));
});
test("an account being deleted cannot be marked", async () => {
  await admin((d) => setDoc(doc(d, "accountDeletions/player"), { status: "deleting" }));
  await assertFails(put(label()));
});
