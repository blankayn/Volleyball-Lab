import test, { before } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

let pending;
before(async () => {
  const result = await build({ entryPoints: [fileURLToPath(new URL("../src/lib/pending.ts", import.meta.url))], bundle: true, platform: "node", format: "cjs", write: false });
  const module = { exports: {} };
  new Function("module", "exports", "require", result.outputFiles[0].text)(module, module.exports, createRequire(import.meta.url));
  pending = module.exports;
});
const gate = () => { let open; const wait = new Promise(resolve => { open = resolve; }); return { wait, open }; };
const settle = () => new Promise(resolve => setTimeout(resolve, 5));

test("a live update cannot cancel the refresh that follows a save", async () => {
  const load = gate(); const painted = [];
  const refresh = pending.createRefresher(async (live, current) => {
    await load.wait;
    if (!current() || live) { painted.push(live ? "live" : "cancelled"); return false; }
    painted.push("save"); return true;
  });
  const saved = refresh();
  assert.equal(await refresh(true), false);
  load.open();
  assert.equal(await saved, true);
  await settle();
  assert.deepEqual(painted, ["save", "live"], "the live update runs after it, not instead of it");
});
test("a refresh after a save replaces a live one already loading", async () => {
  const first = gate(), second = gate(); const results = []; let calls = 0;
  const refresh = pending.createRefresher(async (live, current) => { await (calls++ ? second : first).wait; results.push([live, current()]); return current(); });
  const live = refresh(true), saved = refresh();
  second.open(); assert.equal(await saved, true);
  first.open(); assert.equal(await live, false);
  assert.deepEqual(results, [[false, true], [true, false]]);
});
test("a failed refresh still lets later updates through", async () => {
  let fail = true; let runs = 0;
  const refresh = pending.createRefresher(async () => { runs++; if (fail) throw new Error("offline"); return true; });
  await assert.rejects(refresh());
  fail = false;
  assert.equal(await refresh(true), true);
  assert.equal(runs, 2);
});
test("a slow save is announced, a quick one is not, and failures pass through", async () => {
  let notices = 0;
  assert.equal(await pending.withSlowNotice(Promise.resolve(7), () => notices++, 10), 7);
  await assert.rejects(pending.withSlowNotice(Promise.reject(new Error("no")), () => notices++, 10), /no/);
  await new Promise(resolve => setTimeout(resolve, 25));
  assert.equal(notices, 0);
  await pending.withSlowNotice(new Promise(resolve => setTimeout(resolve, 40)), () => notices++, 10);
  assert.equal(notices, 1);
});
