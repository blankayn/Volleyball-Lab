import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

async function load(path) {
  const result = await build({ entryPoints: [fileURLToPath(new URL(path, import.meta.url))], bundle: true, platform: "node", format: "cjs", write: false,
    plugins: [{ name: "firebase-boundary", setup(builder) {
      builder.onResolve({ filter: /^firebase\/firestore$|^\.\/firebase$|^\.\/account-settings$|^\.\/preferences$/ }, args => ({ path: args.path, namespace: "fixture" }));
      builder.onLoad({ filter: /.*/, namespace: "fixture" }, () => ({ contents: "db auth collection deleteDoc doc getDoc getDocs onSnapshot query serverTimestamp setDoc updateDoc where writeBatch preferencesPath normalizePreferences".split(" ").map(name => `export const ${name} = (...args) => globalThis.__notes?.${name}?.(...args);`).join(";"), loader: "js" }));
    } }] });
  const module = { exports: {} };
  new Function("module", "exports", "require", result.outputFiles[0].text)(module, module.exports, createRequire(import.meta.url));
  return module.exports;
}
let moments, attachments, cloud;
before(async () => {
  moments = await load("../src/lib/note-moment.ts");
  attachments = await load("../src/lib/note-attachments.ts");
  cloud = await load("../src/lib/cloud.ts");
});
after(() => { delete globalThis.__notes; });

const reports = [{ repNumber: 1, startMs: 0, endMs: 2000 }, { repNumber: 4, startMs: 11_000, endMs: 13_000 }];

test("a moment names its rep and reads like the phone does", () => {
  assert.deepEqual(moments.momentAt(12_400.4, reports), { videoMs: 12_400, repNumber: 4 });
  assert.equal(moments.momentLabel(moments.momentAt(12_400, reports)), "Rep 4 · 0:12");
  assert.equal(moments.momentLabel(moments.momentAt(65_000, reports)), "At 1:05");
  assert.deepEqual(moments.momentAt(-5, []), { videoMs: 0, repNumber: null });
});

test("no moment is offered before the recording is loaded", () => {
  assert.equal(moments.recordingMoment(null), null);
  assert.equal(moments.recordingMoment({ hidden: true, src: "blob:x", currentTime: 1 }), null);
  assert.equal(moments.recordingMoment({ hidden: false, src: "", currentTime: 1 }), null);
  const video = { hidden: false, src: "blob:x", currentTime: 12 };
  assert.deepEqual(moments.recordingMoment(video), { videoMs: 12_000, repNumber: null });
  moments.setRecordingReports(video, reports);
  assert.deepEqual(moments.recordingMoment(video), { videoMs: 12_000, repNumber: 4 });
});

test("damaged attachment values never lose the written note", () => {
  const base = { id: "n", coachUid: "c", playerUid: "p", sessionId: "s", text: "Reach.", createdAt: 1 };
  assert.deepEqual(cloud.asNote(base), { ...base, moment: null, hasPhoto: false });
  assert.deepEqual(cloud.asNote({ ...base, videoMs: 5000, repNumber: 2, hasPhoto: true }), { ...base, moment: { videoMs: 5000, repNumber: 2 }, hasPhoto: true });
  for (const bad of [{ videoMs: -1 }, { videoMs: 1.5 }, { videoMs: "9" }, { sessionId: null, videoMs: 5 }, { repNumber: 3 }, { hasPhoto: "yes" }]) {
    const note = cloud.asNote({ ...base, ...bad });
    assert.equal(note.text, "Reach."); assert.equal(note.moment, null); assert.equal(note.hasPhoto, false);
  }
});

test("a plain note writes the same fields as before, and a photo commits with its note", async () => {
  const writes = []; let commits = 0;
  globalThis.__notes = { doc: (_db, ...path) => path.join("/"), getDoc: async () => ({ exists: () => false }),
    writeBatch: () => ({ set: (ref, data) => writes.push([ref, data]), update() {}, commit: async () => { commits++; } }) };
  const note = { id: "n", coachUid: "c", playerUid: "p", sessionId: "s", text: "Reach.", createdAt: 1, moment: null, hasPhoto: false };
  await cloud.postNote(note);
  assert.deepEqual(writes, [["notes/n", { id: "n", coachUid: "c", playerUid: "p", sessionId: "s", text: "Reach.", createdAt: 1 }]]);
  writes.length = 0;
  await cloud.postNote({ ...note, moment: { videoMs: 5000, repNumber: null } }, "data:image/jpeg;base64,AAAA");
  assert.deepEqual(writes.map(w => w[0]), ["notes/n", "noteMedia/n"]);
  assert.equal(writes[0][1].videoMs, 5000); assert.ok(!("repNumber" in writes[0][1])); assert.equal(writes[0][1].hasPhoto, true);
  assert.deepEqual(writes[1][1], { coachUid: "c", playerUid: "p", photo: "data:image/jpeg;base64,AAAA", createdAt: 1 });
  assert.equal(commits, 2);
});

test("only a small JPEG is a photo, and markup is escaped", async () => {
  assert.ok(attachments.validNotePhoto("data:image/jpeg;base64,/9j/4AAQ=="));
  for (const bad of ["", "https://example.com/a.jpg", "data:image/png;base64,AAAA", "data:image/jpeg;base64,AA\">", "data:image/jpeg;base64," + "A".repeat(300_000), null])
    assert.ok(!attachments.validNotePhoto(bad));
  await assert.rejects(attachments.encodeNotePhoto({ type: "application/pdf", size: 10 }), /Choose a photo/);
  await assert.rejects(attachments.encodeNotePhoto({ type: "image/jpeg", size: 16 * 1024 * 1024 }), /15 MB/);
  const html = attachments.noteAttachments({ id: "\"><script>", moment: { videoMs: 1000, repNumber: 1 }, hasPhoto: true }, true);
  assert.ok(!html.includes("<script>")); assert.match(html, /Rep 1 · 0:01/);
});
