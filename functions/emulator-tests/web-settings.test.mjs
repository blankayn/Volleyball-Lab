import test, { before, after, beforeEach } from "node:test";
import { readFile } from "node:fs/promises";
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, updateDoc } from "firebase/firestore";
let env;
before(async () => { env = await initializeTestEnvironment({ projectId: "demo-volleyball-web-settings", firestore: { rules: await readFile(new URL("../../firestore.rules", import.meta.url), "utf8") } }); });
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });
const prefs = () => ({ theme: "system", accent: "rust", density: "comfortable", textSize: "normal", highContrast: false, reduceMotion: false, enhancedFocus: true, submissionAlerts: true, deadlineReminders: true, trainingNotifications: true, emailNotifications: false, photo: "", certifications: "Level 1", updatedAt: Date.now() });
const ref = (uid = "coach", owner = "coach") => doc(env.authenticatedContext(uid).firestore(), `coaches/${owner}/webSettings/preferences`);
test("web settings and profile picture are private to their account", async () => {
  await assertSucceeds(setDoc(ref(), prefs()));
  await assertSucceeds(getDoc(ref()));
  await assertFails(getDoc(ref("other")));
  await assertFails(setDoc(ref("other"), prefs()));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), "coaches/coach/webSettings/preferences")));
  await assertFails(setDoc(doc(env.authenticatedContext("coach").firestore(), "coaches/coach/webSettings/arbitrary"), prefs()));
});
test("invalid theme, executable photo, oversized data and unknown keys are denied", async () => {
  for (const change of [{ theme: "secret" }, { accent: "anything" }, { density: 1 }, { emailNotifications: "true" }, { photo: "javascript:alert(1)" }, { photo: `data:image/svg+xml;base64,AAAA` }, { photo: "x".repeat(120001) }, { certifications: "x".repeat(1201) }, { admin: true }]) await assertFails(setDoc(ref(), { ...prefs(), ...change }));
  await assertSucceeds(setDoc(ref(), { ...prefs(), photo: "data:image/jpeg;base64,/9j/AAAA" }));
  await assertSucceeds(updateDoc(ref(), { theme: "dark", textSize: "larger" }));
});
test("deletion fence blocks settings writes and delivery receipts stay server-only", async () => {
  await env.withSecurityRulesDisabled(async context => { await setDoc(doc(context.firestore(), "accountDeletions/coach"), { status: "deleting" }); });
  await assertFails(setDoc(ref(), prefs()));
  await assertFails(getDoc(doc(env.authenticatedContext("coach").firestore(), "coachEmailDeliveries/event")));
});
