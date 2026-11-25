import test, { before, after, beforeEach } from "node:test";
import { readFile } from "node:fs/promises";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc, writeBatch } from "firebase/firestore";
let env;
before(async () => { env = await initializeTestEnvironment({ projectId: "demo-volleyball-coach-plan", firestore: { rules: await readFile(new URL("../../firestore.rules", import.meta.url), "utf8") } }); });
after(async () => { await env?.cleanup(); });
const admin = (work) => env.withSecurityRulesDisabled((context) => work(context.firestore()));
const db = (uid) => env.authenticatedContext(uid).firestore();
const member = (id, status) => ({ playerUid: id, playerName: id, position: null, status, requestedAt: 1, decidedAt: null });
const setMember = (id, status) => admin((d) => setDoc(doc(d, `roster/coach/members/${id}`), member(id, status)));
const setSlots = (players) => admin((d) => setDoc(doc(d, "coachSlots/coach"), { players, updatedAt: 1 }));
const pro = (expiresAtEpochMs = 0) => admin((d) => setDoc(doc(d, "entitlements/coach"), { status: "active", expiresAtEpochMs }));
beforeEach(async () => { await env.clearFirestore(); });

/** What both clients do: the place and the player in one commit. */
function change(id, status, players, uid = "coach") {
  const own = db(uid);
  const batch = writeBatch(own);
  if (players) batch.set(doc(own, "coachSlots/coach"), { players, updatedAt: 2 });
  batch.update(doc(own, `roster/coach/members/${id}`), { status, decidedAt: 2 });
  return batch.commit();
}

test("a Free coach accepts three students and no more", async () => {
  for (const id of ["a", "b", "c", "d"]) await setMember(id, "Pending");
  await assertSucceeds(change("a", "Active", ["a"]));
  await assertSucceeds(change("b", "Active", ["a", "b"]));
  await assertSucceeds(change("c", "Active", ["a", "b", "c"]));
  await assertFails(change("d", "Active", ["a", "b", "c", "d"]));
  await assertFails(change("d", "Active", null));
  await assertFails(change("d", "Active", ["b", "c", "d"]), "an active student cannot be dropped to make room");
});
test("ending a connection frees a place", async () => {
  for (const id of ["a", "b", "c"]) await setMember(id, "Active");
  await setMember("d", "Pending");
  await setSlots(["a", "b", "c"]);
  await assertSucceeds(change("a", "Ended", ["b", "c"]));
  await assertSucceeds(change("d", "Active", ["b", "c", "d"]));
});
test("a student who left by themself frees a place, and several can be cleared at once", async () => {
  for (const id of ["a", "b", "c"]) await setMember(id, "Ended");
  await setMember("d", "Pending");
  await setSlots(["a", "b", "c"]);
  await assertSucceeds(change("d", "Active", ["d"]));
});
test("Pro has no limit, and an expired Pro keeps its students but adds none", async () => {
  for (const id of ["a", "b", "c", "d"]) await setMember(id, "Active");
  await setMember("e", "Pending"); await setMember("f", "Pending");
  await setSlots(["a", "b", "c", "d"]);
  await pro();
  await assertSucceeds(change("e", "Active", ["a", "b", "c", "d", "e"]));
  await pro(1);
  await assertFails(change("f", "Active", ["a", "b", "c", "d", "e", "f"]));
  await assertSucceeds(updateDoc(doc(db("coach"), "roster/coach/members/a"), { status: "Active", decidedAt: 3 }));
  await assertSucceeds(change("a", "Ended", ["b", "c", "d", "e"]));
  await assertFails(change("f", "Active", ["c", "d", "e", "f"]), "swapping is not allowed while over the limit");
});
test("declining needs no place, and places belong to their coach alone", async () => {
  await setMember("a", "Pending");
  await assertSucceeds(change("a", "Ended", null));
  await setSlots(["a"]);
  await assertSucceeds(getDoc(doc(db("coach"), "coachSlots/coach")));
  for (const uid of ["other", "a"]) {
    await assertFails(getDoc(doc(db(uid), "coachSlots/coach")));
    await assertFails(setDoc(doc(db(uid), "coachSlots/coach"), { players: [], updatedAt: 2 }));
  }
  await assertFails(setDoc(doc(db("coach"), "coachSlots/coach"), { players: ["a"], updatedAt: 2, plan: "pro" }));
  await assertFails(setDoc(doc(db("new"), "coachSlots/new"), { players: ["1", "2", "3", "4"], updatedAt: 2 }));
});
test("a coach profile takes a role and split names, and no longer needs a specialisation", async () => {
  const coach = { name: "Maria C. Santos", firstName: "Maria", middleName: "Cruz", lastName: "Santos", teamName: "Team", organisation: "Club",
    coachingRole: "HeadCoach", yearsExperience: 4, licenceNumber: "", notifySubmissions: true, notifyWeeklyDigest: true, notifySafetyAlerts: true, updatedAt: 1 };
  await assertSucceeds(setDoc(doc(db("coach"), "coaches/coach"), coach));
  for (const bad of [{ coachingRole: "Owner" }, { firstName: "x".repeat(41) }, { middleName: 3 }, { admin: true }])
    await assertFails(setDoc(doc(db("coach"), "coaches/coach"), { ...coach, ...bad }));
  const listing = { name: "Maria C. Santos", teamName: "Team", organisation: "Club", coachingRole: "Trainer", yearsExperience: null, rosterSize: 0, updatedAt: 1 };
  await assertSucceeds(setDoc(doc(db("coach"), "coachDirectory/coach"), listing));
  await assertFails(setDoc(doc(db("coach"), "coachDirectory/coach"), { ...listing, middleName: "Cruz" }));
  await assertSucceeds(setDoc(doc(db("coach"), "coachDirectory/coach"), { ...listing, specialisation: "AllAround" }), "older app versions");
});
