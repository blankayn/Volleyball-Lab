import test, { before } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

let reel;
before(async () => {
  const result = await build({ entryPoints: [fileURLToPath(new URL("../src/lib/coach-reel.ts", import.meta.url))], bundle: true, platform: "node", format: "cjs", write: false });
  const module = { exports: {} };
  new Function("module", "exports", "require", result.outputFiles[0].text)(module, module.exports, createRequire(import.meta.url));
  reel = module.exports;
});
function review(angles = ["Front", "Side"]) {
  return { durationMs: angles.length * 3000, sampleIntervalMs: 100,
    frames: angles.map((_, i) => ({ timestampMs: i * 3000 + 1000, points: Array.from({ length: 33 }, () => ({ x: .5, y: .5, confidence: 1 })) })),
    reps: angles.map((angle, i) => ({ repNumber: i + 1, startMs: i * 3000, endMs: (i + 1) * 3000, angle })),
    issues: angles.map((angle, i) => ({ timestampMs: i * 3000 + 1000, repNumber: i + 1, cueId: "PassKneeFlexion", measured: 170, score: .1 + i / 100, points: [23, 25, 27], angle })) };
}
test("reserves a Side correction even when six Front reps have worse scores", () => {
  const clips = reel.buildCoachReel(review([...Array(7).fill("Front"), "Side"]));
  assert.equal(clips.length, 6);
  assert.ok(clips.some(c => c.rep.angle === "Side"));
  assert.equal(clips[0].rep.repNumber, 1);
});
test("missing, stale, occluded or unmeasured joints do not create correction clips", () => {
  const data = review();
  data.frames[0].points[25].confidence = .1;
  data.frames[1].timestampMs += 400;
  assert.deepEqual(reel.buildCoachReel(data), []);
  data.issues[0].measured = NaN;
  assert.deepEqual(reel.buildCoachReel(data), []);
  assert.deepEqual(reel.buildCoachReel(null), []);
});
test("one correction per rep uses cue weight to break tied scores", () => {
  const data = review(["Front"]);
  data.issues[0].cueId = "PassShouldersLevel";
  data.issues.push({ ...data.issues[0], cueId: "PassPlatformStraight" });
  assert.equal(reel.buildCoachReel(data)[0].issue.cueId, "PassPlatformStraight");
});
test("older shared recordings without rep boundaries get bounded clips", () => {
  const data = review(); delete data.reps;
  const clips = reel.buildCoachReel(data);
  assert.equal(clips.length, 2);
  assert.equal(clips[0].startMs, 0);
  assert.equal(clips[0].endMs, 2200);
});
test("overlapping clips and scores inside the practice target are excluded", () => {
  const data = review();
  data.reps[1].startMs = 1000;
  assert.equal(reel.buildCoachReel(data).length, 1);
  data.issues.forEach(i => { i.score = .8; });
  assert.deepEqual(reel.buildCoachReel(data), []);
});
test("playback follows clip boundaries, full recording clears highlights, disposal cancels playback work", () => {
  const originalWindow = globalThis.window;
  let nextFrame, click, highlight;
  globalThis.window = { requestAnimationFrame(fn) { nextFrame = fn; return 1; }, cancelAnimationFrame() { nextFrame = null; } };
  try {
    const video = Object.assign(new EventTarget(), { currentTime: 0, seeking: false, pause() {}, play() { return Promise.resolve(); } });
    video.addEventListener("reelhighlight", e => { highlight = e.detail; });
    const comment = { textContent: "" };
    const host = { hidden: true, innerHTML: "", querySelector: () => comment, querySelectorAll: () => [], replaceChildren() { this.innerHTML = ""; },
      addEventListener(_event, fn) { click = fn; }, removeEventListener() { click = null; } };
    const dispose = reel.attachCoachReel(video, host, review());
    const press = (kind) => click({ target: { closest: () => ({ dataset: {}, hasAttribute: attribute => attribute === kind }) } });
    press("data-reel-play");
    assert.equal(video.currentTime, 0);
    assert.deepEqual(highlight, [23, 25, 27]);
    assert.match(comment.textContent, /Bend the knees/);
    video.currentTime = 3; nextFrame();
    assert.equal(video.currentTime, 3);
    press("data-reel-full");
    assert.deepEqual(highlight, []);
    assert.equal(nextFrame, null);
    dispose(); assert.equal(click, null); assert.equal(host.hidden, true);
  } finally { globalThis.window = originalWindow; }
});
