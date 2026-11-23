import test, { before } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { build } from "esbuild";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

async function load(path) {
  const result = await build({ entryPoints: [fileURLToPath(new URL(path, import.meta.url))], bundle: true, platform: "node", format: "cjs", write: false });
  const module = { exports: {} };
  new Function("module", "exports", "require", result.outputFiles[0].text)(module, module.exports, createRequire(import.meta.url));
  return module.exports;
}
let notes, cues, reel;
before(async () => {
  notes = await load("../src/lib/rep-notes.ts");
  cues = await load("../src/lib/cues.ts");
  reel = await load("../src/lib/coach-reel.ts");
});

const review = () => ({ durationMs: 9000, reps: [
  { repNumber: 1, startMs: 0, endMs: 2500, formScore: 91 }, { repNumber: 2, startMs: 3000, endMs: 5500, formScore: 52 }, { repNumber: 3, startMs: 6000, endMs: 8500 }],
  issues: [{ timestampMs: 4000, repNumber: 2, cueId: "PassShouldersLevel", measured: 20, score: .6, points: [11, 12] },
    { timestampMs: 4200, repNumber: 2, cueId: "PassKneeFlexion", measured: 168, score: .2, points: [23, 25, 27] }] });

test("every rep is listed and only flagged reps carry notes, worst first", () => {
  const reports = notes.buildRepReports(review());
  assert.deepEqual(reports.map(r => [r.repNumber, r.formScore, r.notes.length]), [[1, 91, 0], [2, 52, 2], [3, null, 0]]);
  assert.equal(reports[1].notes[0].title, "Bend the knees");
  assert.equal(reports[1].notes[0].detail, "168°, above the practice range (110–150°)");
});
test("notes show around their measured moment during normal playback and nowhere else", () => {
  const reports = notes.buildRepReports(review());
  assert.deepEqual(notes.notesAt(reports, 4100).map(n => n.title), ["Bend the knees", "Level the shoulders"]);
  assert.deepEqual(notes.notesAt(reports, 1000), []);
  assert.deepEqual(notes.notesAt(reports, 7000), []);
});
test("a callout never mixes notes from two reps", () => {
  const data = review();
  data.issues.push({ timestampMs: 4300, repNumber: 3, cueId: "PassTrunkAngle", measured: 80, score: .1, points: [11] });
  assert.equal(new Set(notes.notesAt(notes.buildRepReports(data), 4250).map(n => n.repNumber)).size, 1);
});
test("older and damaged reviews stay safe", () => {
  const old = review(); delete old.reps;
  assert.deepEqual(notes.buildRepReports(old).map(r => r.repNumber), [2]);
  const bad = review(); bad.issues[0].score = .9; bad.issues[1].measured = NaN;
  assert.ok(notes.buildRepReports(bad).every(r => r.notes.length === 0));
  const unknown = review(); unknown.issues[0].cueId = "FutureCue";
  assert.ok(notes.buildRepReports(unknown)[1].notes.some(n => n.title === "Outside the practice range"));
  assert.deepEqual(notes.buildRepReports(null), []);
  assert.deepEqual(notes.buildRepReports({ durationMs: 0 }), []);
});
test("the reel holds on the measured moment, and choosing a rep note ends it", () => {
  const originalWindow = globalThis.window, originalTimer = globalThis.setTimeout, originalClear = globalThis.clearTimeout;
  let nextFrame, click, resume;
  globalThis.window = { requestAnimationFrame(fn) { nextFrame = fn; return 1; }, cancelAnimationFrame() { nextFrame = null; } };
  globalThis.setTimeout = fn => { resume = fn; return 1; };
  globalThis.clearTimeout = () => { resume = undefined; };
  try {
    const video = Object.assign(new EventTarget(), { currentTime: 0, seeking: false, paused: true,
      pause() { this.paused = true; this.dispatchEvent(new Event("pause")); }, play() { this.paused = false; this.dispatchEvent(new Event("play")); return Promise.resolve(); } });
    const comment = { textContent: "" };
    const host = { hidden: true, innerHTML: "", querySelector: () => comment, querySelectorAll: () => [], replaceChildren() {}, addEventListener(_e, fn) { click = fn; }, removeEventListener() {} };
    const data = { durationMs: 3000, sampleIntervalMs: 100, frames: [{ timestampMs: 1000, points: Array.from({ length: 33 }, () => ({ x: .5, y: .5, confidence: 1 })) }],
      reps: [{ repNumber: 1, startMs: 0, endMs: 3000 }], issues: [{ timestampMs: 1000, repNumber: 1, cueId: "PassKneeFlexion", measured: 170, score: .1, points: [25] }] };
    const dispose = reel.attachCoachReel(video, host, data);
    click({ target: { closest: () => ({ dataset: {}, hasAttribute: a => a === "data-reel-play" }) } });
    video.currentTime = 1.02; nextFrame();
    assert.equal(video.paused, true);
    assert.equal(video.currentTime, 1);
    resume();
    assert.equal(video.paused, false);
    video.currentTime = 1.2; nextFrame();
    assert.equal(video.paused, false, "it holds once per clip");
    video.dispatchEvent(new Event("repseek"));
    assert.equal(nextFrame, null);
    assert.equal(comment.textContent, "");
    dispose();
  } finally { globalThis.window = originalWindow; globalThis.setTimeout = originalTimer; globalThis.clearTimeout = originalClear; }
});
test("the website knows every cue the Android app can flag, with the same weight and range", async () => {
  const source = await readFile(new URL("../../app/src/main/java/com/volleyballlab/player/domain/model/FormCue.kt", import.meta.url), "utf8");
  const blocks = [...source.matchAll(/FormCue\(\s*id = CueId\.(\w+),[\s\S]*?target = (-?[\d.]+)f\.\.(-?[\d.]+)f,[\s\S]*?weight = ([\d.]+)f/g)];
  assert.ok(blocks.length >= 22);
  for (const [, id, low, high, weight] of blocks) {
    const cue = cues.cueDefinition(id);
    assert.ok(cue, `${id} is missing from coach-web/src/lib/cues.ts`);
    assert.deepEqual([id, ...cue.target, cue.weight], [id, Number(low), Number(high), Number(weight)]);
  }
});
