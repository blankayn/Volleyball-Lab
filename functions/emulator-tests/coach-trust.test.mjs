import test, { before, after, beforeEach } from "node:test";
import { readFile } from "node:fs/promises";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { deleteDoc, doc, getDoc, serverTimestamp, setDoc, Timestamp, updateDoc } from "firebase/firestore";
let env;
before(async () => { env = await initializeTestEnvironment({ projectId: "demo-volleyball-coach-trust", firestore: { rules: await readFile(new URL("../../firestore.rules", import.meta.url), "utf8") } }); });
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    for (const uid of ["coach", "other"]) {
      await setDoc(doc(db, `users/${uid}`), { role: "COACH" });
      await setDoc(doc(db, `coachDirectory/${uid}`), { name: uid });
    }
    await setDoc(doc(db, "users/player"), { role: "PLAYER" });
  });
});
const as = (uid, email = `${uid}@uc.edu.ph`, email_verified = true) => env.authenticatedContext(uid, { email, email_verified }).firestore();
const record = (institutionDomain) => ({ institutionDomain, confirmedAt: serverTimestamp() });
const vouch = (coachUid, voucherUid) => ({ coachUid, voucherUid, createdAt: serverTimestamp() });

test("a coach publishes only the domain their confirmed email proves", async () => {
  await assertSucceeds(setDoc(doc(as("coach"), "coachTrust/coach"), record("uc.edu.ph")));
  await assertSucceeds(setDoc(doc(as("coach", "Coach@UC.edu.ph"), "coachTrust/coach"), record("uc.edu.ph")));
  await assertSucceeds(setDoc(doc(as("coach", "c@gmail.com"), "coachTrust/coach"), record("")));
  await assertSucceeds(getDoc(doc(as("player", "p@gmail.com", false), "coachTrust/coach")));
  await assertFails(setDoc(doc(as("coach", "c@gmail.com"), "coachTrust/coach"), record("uc.edu.ph")));
  await assertFails(setDoc(doc(as("coach", "c@gmail.com"), "coachTrust/coach"), record("gmail.com")));
  await assertFails(setDoc(doc(as("coach", "c@uc.edu.ph.evil.com"), "coachTrust/coach"), record("uc.edu.ph.evil.com")));
  await assertFails(setDoc(doc(as("coach", "coach@uc.edu.ph", false), "coachTrust/coach"), record("uc.edu.ph")));
});
test("nobody publishes for someone else, as a player, with another date or extra fields", async () => {
  await assertFails(setDoc(doc(as("other"), "coachTrust/coach"), record("uc.edu.ph")));
  await assertFails(setDoc(doc(as("player"), "coachTrust/player"), record("uc.edu.ph")));
  await assertFails(setDoc(doc(as("coach"), "coachTrust/coach"), { ...record("uc.edu.ph"), confirmedAt: Timestamp.fromMillis(Date.now() + 9e11) }));
  await assertFails(setDoc(doc(as("coach"), "coachTrust/coach"), { ...record("uc.edu.ph"), verified: true }));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "coachTrust/coach")));
});
test("only a coach with a current institution email can say they know another coach", async () => {
  const path = "coachVouches/other_coach";
  await assertFails(setDoc(doc(as("coach"), path), vouch("other", "coach")));
  await setDoc(doc(as("coach", "c@gmail.com"), "coachTrust/coach"), record(""));
  await assertFails(setDoc(doc(as("coach", "c@gmail.com"), path), vouch("other", "coach")));
  await setDoc(doc(as("coach"), "coachTrust/coach"), record("uc.edu.ph"));
  await assertSucceeds(setDoc(doc(as("coach"), path), vouch("other", "coach")));
  await assertFails(updateDoc(doc(as("coach"), path), { coachUid: "player" }));
  await assertFails(deleteDoc(doc(as("other"), path)));
  await assertSucceeds(deleteDoc(doc(as("coach"), path)));
});
test("the owner's verification can be read by anyone signed in and written by no app", async () => {
  await env.withSecurityRulesDisabled(async (context) => setDoc(doc(context.firestore(), "coachVerified/coach"), { verified: true }));
  await assertSucceeds(getDoc(doc(as("player", "p@gmail.com", false), "coachVerified/coach")));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "coachVerified/coach")));
  await assertFails(setDoc(doc(as("coach"), "coachVerified/coach"), { verified: true }));
  await assertFails(setDoc(doc(as("other"), "coachVerified/other"), { verified: true }));
});
test("a vouch cannot be for oneself, faked for another coach, or aimed at a player", async () => {
  await setDoc(doc(as("coach"), "coachTrust/coach"), record("uc.edu.ph"));
  await assertFails(setDoc(doc(as("coach"), "coachVouches/coach_coach"), vouch("coach", "coach")));
  await assertFails(setDoc(doc(as("other"), "coachVouches/other_coach"), vouch("other", "coach")));
  await assertFails(setDoc(doc(as("coach"), "coachVouches/second_copy"), vouch("other", "coach")));
  await assertFails(setDoc(doc(as("coach"), "coachVouches/player_coach"), vouch("player", "coach")));
});
