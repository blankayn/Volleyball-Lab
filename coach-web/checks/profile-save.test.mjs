import test, { before, after, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { build } from "esbuild";

let directory;
let cloud;
const here = dirname(fileURLToPath(import.meta.url));
before(async () => {
  directory = await mkdtemp(join(tmpdir(), "volleyball-profile-check-"));
  const output = join(directory, "cloud.cjs");
  await build({ entryPoints: [join(here, "../src/lib/cloud.ts")], bundle: true, platform: "node", format: "cjs", outfile: output, plugins: [{ name: "profile-write-boundary", setup(builder) {
    builder.onResolve({ filter: /^firebase\/firestore$|^\.\/firebase$/ }, () => ({ path: join(here, "fixtures/firestore.cjs") }));
  } }] });
  cloud = (await import(pathToFileURL(output).href)).default;
});
after(async () => { delete globalThis.__volleyballProfileSaveCheck; if (directory) await rm(directory, { recursive: true, force: true }); });
beforeEach(() => { globalThis.__volleyballProfileSaveCheck = { writes: [], denySettings: false, denyAccountName: false, denyProfile: false }; });
const state = () => globalThis.__volleyballProfileSaveCheck;
const profile = () => ({ ...cloud.emptyProfile(), name: "Updated coach", teamName: "Updated team", organisation: "Updated school", licenceNumber: "CERT-01" });
const preferences = () => ({ theme: "light", accent: "rust", density: "comfortable", textSize: "normal", highContrast: false, reduceMotion: false, enhancedFocus: true, submissionAlerts: true, deadlineReminders: true, trainingNotifications: true, emailNotifications: false, photo: "data:image/jpeg;base64,/9j/AAAA", certifications: "Level 1" });

const note = () => ({ id: "feedback", coachUid: "coach", playerUid: "player", sessionId: "session", text: "Reach high at contact.", createdAt: 100 });
test("feedback and a pending queue acknowledgement commit together", async () => {
  state().submission = { reviewedAt: null };
  await cloud.postNote(note());
  assert.deepEqual(state().writes.map(write => write.path), ["notes/feedback", "submissions/coach_session"]);
  assert.equal(typeof state().writes[1].data.reviewedAt, "number");
});
test("feedback works for shared sessions without a submission and preserves prior review time", async () => {
  await cloud.postNote(note());
  assert.deepEqual(state().writes.map(write => write.path), ["notes/feedback"]);
  state().writes = [];
  state().submission = { reviewedAt: 42 };
  await cloud.postNote(note());
  assert.deepEqual(state().writes.map(write => write.path), ["notes/feedback"]);
});
test("failed feedback commit propagates without acknowledging the queue", async () => {
  state().submission = { reviewedAt: null };
  state().denyProfile = true;
  await assert.rejects(cloud.postNote(note()), /permissions/);
  assert.deepEqual(state().writes, []);
});

test("profile fields save when older rules deny the newly added settings path", async () => {
  state().denySettings = true;
  const result = await cloud.saveProfile("coach", profile(), 4, preferences());
  assert.equal(result.preferencesSaved, false);
  assert.equal(state().writes.find(write => write.path === "coaches/coach").data.name, "Updated coach");
  assert.equal(state().writes.find(write => write.path === "coachDirectory/coach").data.teamName, "Updated team");
  assert.ok(!state().writes.some(write => write.path.includes("/webSettings/")));
});
test("optional account-name synchronization cannot reject a saved coaching profile", async () => {
  state().denyAccountName = true;
  const result = await cloud.saveProfile("coach", profile(), 4, preferences());
  assert.equal(result.accountNameSynced, false);
  assert.equal(result.preferencesSaved, true);
  assert.equal(state().writes.find(write => write.path === "coaches/coach").data.name, "Updated coach");
});
test("authorized settings store the photo and certifications with a successful result", async () => {
  const result = await cloud.saveProfile("coach", profile(), 4, preferences());
  assert.equal(result.preferencesSaved, true);
  assert.equal(result.accountNameSynced, true);
  const saved = state().writes.find(write => write.path === "coaches/coach/webSettings/preferences");
  assert.equal(saved.data.photo, preferences().photo);
  assert.equal(saved.data.certifications, "Level 1");
});
test("a denied core profile write still fails without claiming success or writing optional data", async () => {
  state().denyProfile = true;
  await assert.rejects(cloud.saveProfile("coach", profile(), 4, preferences()), error => error.code === "permission-denied");
  assert.deepEqual(state().writes, []);
});
test("existing callers without workspace settings remain supported", async () => {
  await cloud.saveProfile("coach", profile(), 4);
  assert.ok(!state().writes.some(write => write.path.includes("/webSettings/")));
  assert.equal(state().writes.find(write => write.path === "coachDirectory/coach").data.rosterSize, 4);
});
