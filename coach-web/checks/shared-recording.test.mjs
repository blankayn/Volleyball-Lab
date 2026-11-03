import test, { before, after, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

let recording;
const auth = { currentUser: null };
const original = { fetch: globalThis.fetch, window: globalThis.window, create: URL.createObjectURL, revoke: URL.revokeObjectURL };
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
  globalThis.window = { setTimeout, clearTimeout };
  URL.createObjectURL = blob => { assert.equal(blob.type, "video/mp4"); globalThis.__r2WebCheck.urls.push(blob); return "blob:private-video"; };
  URL.revokeObjectURL = url => globalThis.__r2WebCheck.revoked.push(url);
});
afterEach(() => { globalThis.fetch = original.fetch; globalThis.window = original.window; URL.createObjectURL = original.create; URL.revokeObjectURL = original.revoke; });
after(() => { delete globalThis.__r2WebCheck; });
function ui() {
  const video = { hidden: true, src: "", pause() {}, removeAttribute() { this.src = ""; }, load() {} };
  const status = { textContent: "" };
  const panel = { isConnected: true, dataset: { recordingPanel: "session" }, querySelector: selector => selector === "video" ? video : status };
  const button = { disabled: false, hidden: false, closest: () => panel };
  let click;
  const root = { addEventListener(_name, listener) { click = listener; }, removeEventListener() {}, querySelectorAll: () => [video] };
  return { video, status, button, root, click: () => click({ target: { closest: () => button } }) };
}

test("coach video loads with bearer authorization and its private object URL is cleared on sign-out", async () => {
  const f = ui();
  globalThis.fetch = async (url, init) => {
    assert.equal(url, "https://storage.example/recordings/session/video.mp4");
    assert.equal(init.headers.Authorization, "Bearer signed-firebase-token");
    assert.equal(init.redirect, "error"); assert.equal(init.cache, "no-store");
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
