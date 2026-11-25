import test, { before, after, beforeEach } from "node:test";
import { readFile } from "node:fs/promises";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc, updateDoc, writeBatch } from "firebase/firestore";
let env;
before(async () => { env = await initializeTestEnvironment({ projectId: "demo-volleyball-assessment", firestore: { rules: await readFile(new URL("../../firestore.rules", import.meta.url), "utf8") } }); });
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });
const db = (uid) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).firestore();
const answers = { experience: "New", position: null, preferred: ["Setting"], goal: "BeConsistent", goalNote: "", days: 3, minutes: 30, weakPoints: [], weakNote: "", statements: { serving_ready: "Yes" }, savedAtEpochMs: 1 };
const plan = { id: "p1", createdAtEpochMs: 2, analysis: { summary: "Text", strengths: [], weaknesses: [], improvements: [], focus: ["Setting"], practices: [], weeklyGoals: [] }, measured: [], checks: [], changes: [] };
const record = (extra = {}) => ({ version: 1, answers, plan, updatedAt: 2, ...extra });

test("an athlete saves and reads their own assessment and plan history, and nobody else can", async () => {
  const own = db("player"), batch = writeBatch(own);
  batch.set(doc(own, "athleteAssessments/player"), record());
  batch.set(doc(own, "athleteAssessments/player/plans/p1"), plan);
  await assertSucceeds(batch.commit());
  await assertSucceeds(getDoc(doc(own, "athleteAssessments/player")));
  await assertSucceeds(getDocs(collection(own, "athleteAssessments/player/plans")));
  for (const uid of ["coach", null]) {
    await assertFails(getDoc(doc(db(uid), "athleteAssessments/player")));
    await assertFails(getDocs(collection(db(uid), "athleteAssessments/player/plans")));
    await assertFails(setDoc(doc(db(uid), "athleteAssessments/player"), record()));
    await assertFails(setDoc(doc(db(uid), "athleteAssessments/player/plans/x"), { ...plan, id: "x" }));
  }
});
test("answers can wait for a plan, and a saved plan is never rewritten", async () => {
  await assertSucceeds(setDoc(doc(db("player"), "athleteAssessments/player"), { version: 1, answers, plan: null, updatedAt: 1 }));
  await assertSucceeds(setDoc(doc(db("player"), "athleteAssessments/player"), { version: 1, answers: { ...answers, days: 5 }, updatedAt: 3 }, { merge: true }));
  await assertSucceeds(setDoc(doc(db("player"), "athleteAssessments/player/plans/p1"), plan));
  await assertFails(updateDoc(doc(db("player"), "athleteAssessments/player/plans/p1"), { changes: ["edited"] }));
  await assertSucceeds(deleteDoc(doc(db("player"), "athleteAssessments/player")));
});
test("the skills chosen and what happens in them can be saved, and older answers without it still can", async () => {
  await assertSucceeds(setDoc(doc(db("player"), "athleteAssessments/player"), record({ answers: { ...answers, details: { serving_ready: ["serving_ready_1"] } } })));
  await assertSucceeds(setDoc(doc(db("player"), "athleteAssessments/player"), record()));
  for (const details of ["x", Object.fromEntries(Array.from({ length: 21 }, (_, i) => [`s${i}`, ["a"]]))])
    await assertFails(setDoc(doc(db("player"), "athleteAssessments/player"), record({ answers: { ...answers, details } })));
});
test("unknown fields, wrong types and oversized values are refused", async () => {
  for (const bad of [{ version: 2 }, { name: "Ana" }, { answers: "x" }, { answers: { ...answers, email: "x" } }, { answers: { ...answers, goalNote: "x".repeat(161) } },
    { answers: { ...answers, days: 9 } }, { answers: { ...answers, statements: Object.fromEntries(Array.from({ length: 21 }, (_, i) => [`s${i}`, "Yes"])) } },
    { plan: "x" }, { plan: { ...plan, score: 99 } }, { plan: { ...plan, changes: Array(7).fill("x") } }, { plan: { ...plan, analysis: { ...plan.analysis, score: 99 } } }, { updatedAt: "now" }])
    await assertFails(setDoc(doc(db("player"), "athleteAssessments/player"), record(bad)), JSON.stringify(bad));
  await assertFails(setDoc(doc(db("player"), "athleteAssessments/player/plans/other"), plan), "the id must match");
});
test("an account being deleted cannot save", async () => {
  await env.withSecurityRulesDisabled((context) => setDoc(doc(context.firestore(), "accountDeletions/player"), { status: "deleting" }));
  await assertFails(setDoc(doc(db("player"), "athleteAssessments/player"), record()));
});
