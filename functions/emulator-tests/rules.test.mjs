import test, { before, after, beforeEach } from "node:test";
import { readFile } from "node:fs/promises";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { doc, getDoc, getDocs, setDoc, updateDoc, deleteDoc, deleteField, collection, collectionGroup, query, where, runTransaction, arrayUnion } from "firebase/firestore";
import { OWNER_UID } from "../billing-policy.mjs";

let env;
before(async () => {
  env = await initializeTestEnvironment({ projectId: "demo-volleyball-lab", firestore: { rules: await readFile(new URL("../../firestore.rules", import.meta.url), "utf8") } });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); await seed("coachDirectory/coach", directory()); });

async function seed(path, data) { await env.withSecurityRulesDisabled(async context => setDoc(doc(context.firestore(), path), data)); }
function client(uid) { return env.authenticatedContext(uid).firestore(); }

test("push registrations are write-only, bound to the signed-in account and removed only by their owner", async () => {
  const registration = { uid: "player", token: "fid-device", platform: "android", updatedAt: Date.now() };
  await assertSucceeds(setDoc(doc(client("player"), "pushTokens/fid-device"), registration));
  await assertFails(getDoc(doc(client("player"), "pushTokens/fid-device")));
  await assertFails(getDocs(collection(client("player"), "pushTokens")));
  await assertFails(setDoc(doc(client("other"), "pushTokens/fid-device"), registration));
  await assertFails(deleteDoc(doc(client("other"), "pushTokens/fid-device")));
  await assertFails(setDoc(doc(env.unauthenticatedContext().firestore(), "pushTokens/fid-device"), registration));
  await assertFails(setDoc(doc(client("player"), "pushTokens/other-device"), registration));
  await assertFails(setDoc(doc(client("player"), "pushTokens/fid-device"), { ...registration, secret: "extra" }));
  await assertSucceeds(deleteDoc(doc(client("player"), "pushTokens/fid-device")));
});

test("a device can move to its newly signed-in account, but deleting accounts cannot register", async () => {
  await seed("pushTokens/fid-device", { uid: "previous", token: "fid-device", platform: "android", updatedAt: Date.now() });
  await assertSucceeds(setDoc(doc(client("player"), "pushTokens/fid-device"), {
    uid: "player", token: "fid-device", platform: "android", updatedAt: Date.now(),
  }));
  await seed("accountDeletions/player", { status: "deleting" });
  await assertFails(setDoc(doc(client("player"), "pushTokens/fid-device"), {
    uid: "player", token: "fid-device", platform: "android", updatedAt: Date.now(),
  }));
});
const active = { ownerUid: "paid", status: "active", expiresAtEpochMs: Date.now() + 86400000 };
const now = Date.now();
function directory() { return { name: "Coach", teamName: "Team", specialisation: null, yearsExperience: null, rosterSize: 0, updatedAt: now }; }
function member(uid, status = "Pending") { return { playerUid: uid, playerName: "Athlete", position: null, status, requestedAt: now, decidedAt: null }; }
function session(id = "s", ownerUid = "paid") { return { id, ownerUid, skill: "Receiving", drillId: "d", startedAt: now, durationMs: 10000,
  repCount: 0, formScore: null, weakestCue: null, reps: [], sharedWith: [], createdAt: now, updatedAt: now }; }
function assignment(id = "a") { return { id, coachUid: "coach", playerUid: "paid", title: "Practice", skill: "Receiving", level: "Beginner", summary: "",
  instructions: "", targetSets: 3, targetReps: 10, dueAt: null, status: "Pending", createdAt: now, libraryDrillId: null }; }

async function sendAssignmentResult(uid = "paid") {
  const db = client(uid);
  return runTransaction(db, async tx => {
    const assignmentRef = doc(db, "assignments/a");
    const memberRef = doc(db, `roster/coach/members/${uid}`);
    const sessionRef = doc(db, "sessions/s");
    const receiptRef = doc(db, "submissions/coach_s");
    await tx.get(assignmentRef);
    await tx.get(memberRef);
    await tx.get(sessionRef);
    const receipt = await tx.get(receiptRef);
    if (!receipt.exists()) tx.set(receiptRef, { sessionId: "s", coachUid: "coach", playerUid: uid, submittedAt: now, reviewedAt: null });
    tx.update(sessionRef, { sharedWith: arrayUnion("coach") });
    tx.update(assignmentRef, { status: "Completed" });
  });
}

test("assignment submission atomically shares results and completes the task; retry preserves review", async () => {
  await seed("roster/coach/members/paid", member("paid", "Active"));
  await seed("sessions/s", session());
  await seed("assignments/a", assignment());
  await assertSucceeds(sendAssignmentResult());
  const coach = client("coach");
  const receipt = doc(coach, "submissions/coach_s");
  await assertSucceeds(getDoc(doc(coach, "sessions/s")));
  const completed = await getDoc(doc(client("paid"), "assignments/a"));
  if (completed.data().status !== "Completed") throw new Error("Task was not completed");
  await updateDoc(receipt, { reviewedAt: now + 1 });
  await assertSucceeds(sendAssignmentResult());
  if ((await getDoc(receipt)).data().reviewedAt !== now + 1) throw new Error("Retry removed coach review");
});

test("a disconnected player's submission fails without completing or sharing the task", async () => {
  await seed("roster/coach/members/paid", member("paid", "Ended"));
  await seed("sessions/s", session());
  await seed("assignments/a", assignment());
  await assertFails(sendAssignmentResult());
  const db = client("paid");
  if ((await getDoc(doc(db, "assignments/a"))).data().status !== "Pending") throw new Error("Failed send completed task");
  if ((await getDoc(doc(db, "sessions/s"))).data().sharedWith.length) throw new Error("Failed send shared result");
  if ((await getDoc(doc(db, "submissions/coach_s"))).exists()) throw new Error("Failed send created a receipt");
});

test("first-send absence checks require sign-in and never expose existing results to other users", async () => {
  const db = client("paid");
  await assertSucceeds(getDoc(doc(db, "sessions/new")));
  await assertSucceeds(getDoc(doc(db, "submissions/coach_new")));
  const anonymous = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(anonymous, "sessions/new")));
  await assertFails(getDoc(doc(anonymous, "submissions/coach_new")));
  await seed("sessions/s", session());
  await seed("submissions/coach_s", { sessionId: "s", coachUid: "coach", playerUid: "paid", submittedAt: now, reviewedAt: null });
  const other = client("other");
  await assertFails(getDoc(doc(other, "sessions/s")));
  await assertFails(getDoc(doc(other, "submissions/coach_s")));
  await assertFails(getDocs(collection(other, "sessions")));
  await assertFails(getDocs(collection(other, "submissions")));
});

test("entitlements allow own reads, deny another user and unsigned reads", async () => {
  await seed("entitlements/paid", active);
  await assertSucceeds(getDoc(doc(client("paid"), "entitlements/paid")));
  await assertFails(getDoc(doc(client("other"), "entitlements/paid")));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "entitlements/paid")));
});
test("a client cannot self-grant, modify expiry, revoke, or delete entitlement", async () => {
  const ref = doc(client("paid"), "entitlements/paid");
  await assertFails(setDoc(ref, active));
  await seed("entitlements/paid", active);
  await assertFails(updateDoc(ref, { expiresAtEpochMs: 0 }));
  await assertFails(updateDoc(ref, { status: "active", ownerUid: "other" }));
  await assertFails(deleteDoc(ref));
});

test("temporary demo code unlocks Pro coach requests only for the signed-in account", async () => {
  const db = client("demo-player");
  const access = doc(db, "demoProAccess/demo-player");
  await assertFails(setDoc(doc(db, "roster/coach/members/demo-player"), member("demo-player")));
  await assertFails(setDoc(access, { code: "000000" }));
  await assertFails(setDoc(doc(db, "demoProAccess/other"), { code: "123456" }));
  await assertFails(setDoc(doc(env.unauthenticatedContext().firestore(), "demoProAccess/demo-player"), { code: "123456" }));
  await assertSucceeds(setDoc(access, { code: "123456" }));
  await assertSucceeds(getDoc(access));
  await assertSucceeds(setDoc(doc(db, "roster/coach/members/demo-player"), member("demo-player")));
  await assertFails(getDoc(doc(client("other"), "demoProAccess/demo-player")));
});
test("billing configuration is signed-in read only and internal billing data stays private", async () => {
  await seed("billingConfig/pro", { enabled: false, priceCentavos: 0 });
  await assertSucceeds(getDoc(doc(client("u"), "billingConfig/pro")));
  await assertFails(setDoc(doc(client("u"), "billingConfig/pro"), { enabled: true }));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "billingConfig/pro")));
  for (const collection of ["billingPending", "billingSessions", "billingPayments"]) {
    await seed(`${collection}/u`, { ownerUid: "u" });
    await assertFails(getDoc(doc(client("u"), `${collection}/u`)));
    await assertFails(setDoc(doc(client("u"), `${collection}/u`), { ownerUid: "u" }));
  }
});
test("Free and expired accounts cannot send new Pro-only coach requests", async () => {
  await assertFails(setDoc(doc(client("free"), "roster/coach/members/free"), member("free")));
  await seed("entitlements/expired", { ownerUid: "expired", status: "active", expiresAtEpochMs: Date.now() - 1000 });
  await assertFails(setDoc(doc(client("expired"), "roster/coach/members/expired"), member("expired")));
});
test("owner and paid users can request their own coach connection while identity forgery fails", async () => {
  await assertSucceeds(setDoc(doc(client(OWNER_UID), `roster/coach/members/${OWNER_UID}`), member(OWNER_UID)));
  await seed("entitlements/paid", active);
  await assertSucceeds(setDoc(doc(client("paid"), "roster/coach/members/paid"), member("paid")));
  await assertFails(setDoc(doc(client("paid"), "roster/coach/members/other"), member("other")));
  await assertFails(updateDoc(doc(client("paid"), "roster/coach/members/paid"), { status: "Active" }));
  await assertSucceeds(updateDoc(doc(client("coach"), "roster/coach/members/paid"), { status: "Active" }));
});

test("billing order ownership and private coach profiles do not leak through directory reads", async () => {
  await seed("billingOrders/order", { ownerUid: "paid", status: "pending" });
  await assertSucceeds(getDoc(doc(client("paid"), "billingOrders/order")));
  await assertFails(getDoc(doc(client("other"), "billingOrders/order")));
  await assertFails(updateDoc(doc(client("paid"), "billingOrders/order"), { status: "paid" }));
  await seed("coaches/coach", { licenceNumber: "Private", notifySubmissions: true });
  await assertSucceeds(getDoc(doc(client("coach"), "coaches/coach")));
  await assertFails(getDoc(doc(client("paid"), "coaches/coach")));
  await assertSucceeds(getDocs(collection(client("paid"), "coachDirectory")));
  await assertFails(getDocs(collection(env.unauthenticatedContext().firestore(), "coachDirectory")));
});

test("a declined student can request again; member queries remain account scoped", async () => {
  await seed("entitlements/paid", active);
  await seed("roster/coach/members/paid", member("paid", "Ended"));
  await assertSucceeds(setDoc(doc(client("paid"), "roster/coach/members/paid"), { ...member("paid"), requestedAt: now + 1 }));
  await assertSucceeds(getDocs(query(collectionGroup(client("paid"), "members"), where("playerUid", "==", "paid"))));
  await assertSucceeds(getDocs(collection(client("coach"), "roster/coach/members")));
  await assertFails(getDocs(collectionGroup(client("paid"), "members")));
  await assertFails(updateDoc(doc(client("coach"), "roster/coach/members/paid"), { playerUid: "victim" }));
  await assertFails(updateDoc(doc(client("paid"), "roster/coach/members/paid"), { playerName: "x".repeat(1000), status: "Ended" }));
  await assertSucceeds(updateDoc(doc(client("paid"), "roster/coach/members/paid"), { status: "Ended", decidedAt: now + 2 }));
  await seed("entitlements/paid", { ...active, status: "revoked" });
  await assertFails(setDoc(doc(client("paid"), "roster/coach/members/paid"), member("paid")));
});

test("assignments validate create and update without allowing identity or schema changes", async () => {
  await seed("roster/coach/members/paid", member("paid", "Active"));
  const coachRef = doc(client("coach"), "assignments/a");
  await assertSucceeds(setDoc(coachRef, assignment()));
  await assertFails(updateDoc(coachRef, { playerUid: "victim" }));
  await assertFails(updateDoc(coachRef, { instructions: "x".repeat(1201) }));
  await assertFails(updateDoc(coachRef, { title: deleteField() }));
  await assertFails(updateDoc(coachRef, { admin: true }));
  await assertFails(updateDoc(coachRef, { targetReps: "10" }));
  await assertFails(updateDoc(doc(client("paid"), "assignments/a"), { targetSets: 10 }));
  await assertSucceeds(updateDoc(doc(client("paid"), "assignments/a"), { status: "Completed" }));
  await assertSucceeds(getDocs(query(collection(client("paid"), "assignments"), where("playerUid", "==", "paid"))));
  await assertSucceeds(getDocs(query(collection(client("coach"), "assignments"), where("coachUid", "==", "coach"))));
  await assertFails(getDocs(collection(client("paid"), "assignments")));
});

test("submissions require an owned session and cannot redirect identity on requeue", async () => {
  await seed("roster/coach/members/paid", member("paid", "Active"));
  await seed("sessions/s", session());
  const row = { sessionId: "s", coachUid: "coach", playerUid: "paid", submittedAt: now, reviewedAt: null };
  const playerRef = doc(client("paid"), "submissions/coach_s");
  await assertSucceeds(setDoc(playerRef, row));
  await assertSucceeds(updateDoc(doc(client("coach"), "submissions/coach_s"), { reviewedAt: now + 1 }));
  await assertFails(updateDoc(playerRef, { playerUid: "victim", reviewedAt: null }));
  await assertFails(updateDoc(playerRef, { coachUid: "other", reviewedAt: null }));
  await assertSucceeds(setDoc(playerRef, { ...row, submittedAt: now + 2 }));
  await seed("sessions/other", session("other", "victim"));
  await assertFails(setDoc(doc(client("paid"), "submissions/coach_other"), { ...row, sessionId: "other" }));
  await assertSucceeds(getDocs(query(collection(client("coach"), "submissions"), where("coachUid", "==", "coach"))));
  await assertSucceeds(getDocs(query(collection(client("paid"), "submissions"), where("playerUid", "==", "paid"))));
});

test("session ownership, explicit sharing and legacy sessions remain readable", async () => {
  const ref = doc(client("paid"), "sessions/s");
  await assertSucceeds(setDoc(ref, session()));
  await assertFails(updateDoc(ref, { ownerUid: "victim" }));
  await assertFails(updateDoc(ref, { createdAt: now - 1 }));
  await assertFails(updateDoc(ref, { sharedWith: [123] }));
  await assertFails(updateDoc(ref, { skill: "other" }));
  await assertFails(updateDoc(ref, { durationMs: -1 }));
  await assertFails(updateDoc(ref, { reps: "invalid" }));
  await assertFails(updateDoc(ref, { admin: true }));
  const stages = ["Front", "Side", "Rear"].map((angle, stageIndex) => ({ stageIndex, angle,
    startMs: stageIndex * 10000, endMs: (stageIndex + 1) * 10000, liveRepCount: 10, validRepCount: 8,
    bestStreak: 5, estimatedFormScore: 85 }));
  await assertSucceeds(updateDoc(ref, { angleStages: stages }));
  await assertFails(updateDoc(ref, { angleStages: [{ ...stages[0], bestStreak: 11 }] }));
  await assertSucceeds(updateDoc(ref, { sharedWith: ["coach"] }));
  await assertFails(updateDoc(ref, { sharedWith: [] }));
  await assertSucceeds(getDocs(query(collection(client("coach"), "sessions"), where("sharedWith", "array-contains", "coach"))));
  await assertSucceeds(getDocs(query(collection(client("paid"), "sessions"), where("ownerUid", "==", "paid"))));
  await assertFails(getDoc(doc(client("other"), "sessions/s")));
});

test("private profile and public projection accept valid writes but reject leaked fields and oversized updates", async () => {
  const coachRef = doc(client("coach"), "coaches/coach");
  const profile = { name: "Coach", teamName: "Team", organisation: "Club", specialisation: "AllAround", yearsExperience: 5,
    licenceNumber: "Private", notifySubmissions: true, notifyWeeklyDigest: true, notifySafetyAlerts: true, updatedAt: now };
  await assertSucceeds(setDoc(coachRef, profile));
  await assertSucceeds(updateDoc(coachRef, { notifyWeeklyDigest: false }));
  await assertFails(updateDoc(coachRef, { organisation: "x".repeat(49) }));
  await assertFails(updateDoc(coachRef, { name: deleteField() }));
  await assertFails(updateDoc(doc(client("paid"), "coaches/coach"), { name: "Other" }));
  const directoryRef = doc(client("coach"), "coachDirectory/coach");
  await assertSucceeds(setDoc(directoryRef, { ...directory(), organisation: "Club" }));
  await assertFails(updateDoc(directoryRef, { licenceNumber: "Private" }));
  await assertFails(updateDoc(directoryRef, { email: "private@example.com" }));
  await assertFails(updateDoc(directoryRef, { rosterSize: -1 }));
});

test("notes are bounded and queries read only the participant's feedback", async () => {
  await seed("roster/coach/members/paid", member("paid", "Active"));
  const note = { id: "n", coachUid: "coach", playerUid: "paid", sessionId: null, text: "Keep working on your platform.", createdAt: now };
  await assertSucceeds(setDoc(doc(client("coach"), "notes/n"), note));
  await assertFails(setDoc(doc(client("coach"), "notes/long"), { ...note, id: "long", text: "x".repeat(801) }));
  await assertFails(setDoc(doc(client("coach"), "notes/victim"), { ...note, id: "victim", playerUid: "victim" }));
  await assertFails(updateDoc(doc(client("coach"), "notes/n"), { text: "Changed" }));
  await assertSucceeds(getDocs(query(collection(client("paid"), "notes"), where("playerUid", "==", "paid"))));
  await assertSucceeds(getDocs(query(collection(client("coach"), "notes"), where("coachUid", "==", "coach"), where("playerUid", "==", "paid"))));
  await assertFails(getDocs(collection(client("paid"), "notes")));
});

test("account rows remain private and cannot introduce a billing administrator role", async () => {
  const ref = doc(client("coach"), "users/coach");
  const row = { role: "COACH", displayName: "Coach", email: "coach@example.com", createdAt: now, updatedAt: now };
  await assertSucceeds(setDoc(ref, row));
  await assertFails(updateDoc(ref, { role: "ADMIN" }));
  await assertFails(updateDoc(ref, { isAdmin: true }));
  await assertFails(updateDoc(ref, { displayName: "x".repeat(1000) }));
  await assertFails(updateDoc(ref, { createdAt: now + 1 }));
  await assertFails(getDoc(doc(client("paid"), "users/coach")));
  await assertFails(getDocs(collection(env.unauthenticatedContext().firestore(), "users")));
});

test("Pro request reaches coach, approval reaches student, and assignment and feedback work both ways", async () => {
  await seed("entitlements/paid", active);
  const player = client("paid"), coach = client("coach");
  await assertSucceeds(setDoc(doc(player, "roster/coach/members/paid"), member("paid")));
  const pending = await assertSucceeds(getDocs(collection(coach, "roster/coach/members")));
  if (pending.size !== 1 || pending.docs[0].data().status !== "Pending") throw new Error("Coach did not receive request");
  await assertSucceeds(updateDoc(doc(coach, "roster/coach/members/paid"), { status: "Active", decidedAt: now + 1 }));
  const link = await assertSucceeds(getDocs(query(collectionGroup(player, "members"), where("playerUid", "==", "paid"))));
  if (link.size !== 1 || link.docs[0].data().status !== "Active") throw new Error("Student did not receive approval");
  await assertSucceeds(setDoc(doc(coach, "assignments/a"), assignment()));
  const tasks = await assertSucceeds(getDocs(query(collection(player, "assignments"), where("playerUid", "==", "paid"))));
  if (tasks.size !== 1) throw new Error("Student did not receive assignment");
  await assertSucceeds(updateDoc(doc(player, "assignments/a"), { status: "Completed" }));
  const completed = await assertSucceeds(getDoc(doc(coach, "assignments/a")));
  if (completed.data().status !== "Completed") throw new Error("Coach did not receive completion");
  const note = { id: "interaction", coachUid: "coach", playerUid: "paid", sessionId: null, text: "Keep your platform steady.", createdAt: now };
  await assertSucceeds(setDoc(doc(coach, "notes/interaction"), note));
  const feedback = await assertSucceeds(getDocs(query(collection(player, "notes"), where("playerUid", "==", "paid"))));
  if (feedback.size !== 1 || feedback.docs[0].data().text !== note.text) throw new Error("Student did not receive feedback");
});
