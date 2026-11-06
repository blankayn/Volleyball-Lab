import test, { before, after, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

let recording;
const auth = { currentUser: null };
const original = { fetch: globalThis.fetch, window: globalThis.window, resize: globalThis.ResizeObserver, create: URL.createObjectURL, revoke: URL.revokeObjectURL };
before(async () => {
  globalThis.__r2WebCheck = { auth };
  const result = await build({ entryPoints: [fileURLToPath(new URL("../src/lib/shared-recording.ts", import.meta.url))], bundle: true, platform: "node", format: "cjs", write: false,
    define: { "import.meta.env.VITE_RECORDING_STORAGE_URL": '"https://storage.example"' },
    plugins: [{ name: "recording-auth-boundary", setup(builder) {
      builder.onResolve({ filter: /^firebase\/auth$|^\.\/firebase$/ }, args => ({ path: args.path, namespace: "auth-fixture" }));
      builder.onLoad({ filter: /.*/, namespace: "auth-fixture" }, () => ({ contents: `export const auth = globalThis.__r2WebCheck.auth; export function onAuthStateChanged(_auth, callback) { globalThis.__r2WebCheck.changed = callback; return () => {}; }`, loader: "js" }));
    } }] });
  const module = { exports: {} };
  new Function("module", "exports", "require", result.outputFiles[0].text)(module, module.exports, createRequire(import.meta.url));
  recording = module.exports;
});
beforeEach(() => {
  globalThis.__r2WebCheck = { auth, urls: [], revoked: [] };
  auth.currentUser = { uid: "coach", getIdToken: async () => "signed-firebase-token" };
  globalThis.window = { setTimeout, clearTimeout, cancelAnimationFrame() {} };
  globalThis.ResizeObserver = class { observe() {} disconnect() {} };
  URL.createObjectURL = blob => { assert.equal(blob.type, "video/mp4"); globalThis.__r2WebCheck.urls.push(blob); return "blob:private-video"; };
  URL.revokeObjectURL = url => globalThis.__r2WebCheck.revoked.push(url);
});
afterEach(() => { globalThis.fetch = original.fetch; globalThis.window = original.window; globalThis.ResizeObserver = original.resize; URL.createObjectURL = original.create; URL.revokeObjectURL = original.revoke; });
after(() => { delete globalThis.__r2WebCheck; });
function ui() {
  const video = Object.assign(new EventTarget(), { hidden: true, src: "", paused: true, currentTime: 0,
    videoWidth: 100, videoHeight: 200, pause() {}, removeAttribute() { this.src = ""; }, load() {} });
  const status = { textContent: "" };
  const toggle = Object.assign(new EventTarget(), { checked: true });
  const option = { hidden: true, querySelector: () => toggle };
  const drawn = [];
  const canvas = { clientWidth: 200, clientHeight: 200, getContext: () => ({
    clearRect() { drawn.length = 0; }, arc(x, y) { drawn.push([x, y]); },
    setTransform() {}, beginPath() {}, moveTo() {}, lineTo() {}, stroke() {}, fill() {},
  }) };
  const nodes = { video, "[data-recording-status]": status, "[data-recording-skeleton]": canvas, "[data-skeleton-option]": option };
  const panel = { isConnected: true, dataset: { recordingPanel: "session" }, querySelector: selector => nodes[selector] };
  const button = { disabled: false, hidden: false, closest: () => panel };
  let click;
  const root = { addEventListener(_name, listener) { click = listener; }, removeEventListener() {},
    querySelectorAll: selector => selector === "[data-skeleton-option]" ? [option] : [video] };
  return { video, status, button, root, option, toggle, drawn, click: () => click({ target: { closest: () => button } }) };
}

test("coach video loads with bearer authorization and its private object URL is cleared on sign-out", async () => {
  const f = ui();
  globalThis.fetch = async (url, init) => {
    assert.equal(init.headers.Authorization, "Bearer signed-firebase-token");
    assert.equal(init.redirect, "error"); assert.equal(init.cache, "no-store");
    if (url.endsWith("/review.json")) return new Response(null, { status: 404 });
    assert.equal(url, "https://storage.example/recordings/session/video.mp4");
    return new Response("synthetic-video", { headers: { "Content-Type": "video/mp4" } });
  };
  const dispose = recording.wireSharedRecordings(f.root);
  await f.click();
  assert.equal(f.video.src, "blob:private-video"); assert.equal(f.video.hidden, false);
  auth.currentUser = null; globalThis.__r2WebCheck.changed(null);
  assert.equal(f.video.src, ""); assert.equal(f.video.hidden, true);
  assert.deepEqual(globalThis.__r2WebCheck.revoked, ["blob:private-video"]);
  dispose();
});

test("private pose frames fit the video, follow seeking, and clear on sign-out", async () => {
  const f = ui();
  const requests = [];
  globalThis.fetch = async (url, init) => {
    requests.push([url, init.headers.Authorization]);
    if (url.endsWith("/review.json")) return Response.json({ sampleIntervalMs: 100, frames: [
      { timestampMs: 0, points: Array.from({ length: 33 }, () => ({ x: 0.25, y: 0.5, confidence: 1 })) },
      { timestampMs: 100, points: [] },
    ] });
    return new Response("synthetic-video", { headers: { "Content-Type": "video/mp4" } });
  };
  const dispose = recording.wireSharedRecordings(f.root);
  await f.click();
  assert.deepEqual(requests, ["video.mp4", "review.json"].map(name =>
    [`https://storage.example/recordings/session/${name}`, "Bearer signed-firebase-token"]));
  assert.equal(f.option.hidden, false);
  assert.deepEqual(f.drawn[0], [75, 100]);
  f.video.currentTime = 0.1;
  f.video.dispatchEvent(new Event("seeked"));
  assert.equal(f.drawn.length, 0);
  f.video.currentTime = 0;
  f.video.dispatchEvent(new Event("seeked"));
  assert.equal(f.drawn.length, 22);
  auth.currentUser = null;
  globalThis.__r2WebCheck.changed(null);
  assert.equal(f.drawn.length, 0);
  assert.equal(f.option.hidden, true);
  dispose();
});

test("summary-only sessions show a clear message and allow retry", async () => {
  const f = ui(); globalThis.fetch = async () => new Response(null, { status: 404 });
  const dispose = recording.wireSharedRecordings(f.root); await f.click();
  assert.match(f.status.textContent, /No cloud recording was sent/);
  assert.equal(f.button.disabled, false); assert.equal(f.video.hidden, true); dispose();
});

test("an account switch during download cannot attach the former coach's recording", async () => {
  const f = ui();
  globalThis.fetch = async () => {
    auth.currentUser = { uid: "other" };
    globalThis.__r2WebCheck.changed(auth.currentUser);
    return new Response("synthetic-video", { headers: { "Content-Type": "video/mp4" } });
  };
  const dispose = recording.wireSharedRecordings(f.root); await f.click();
  assert.equal(globalThis.__r2WebCheck.urls.length, 0); assert.equal(f.video.hidden, true); dispose();
});
