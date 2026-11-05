import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

let dashboard;
before(async () => {
  const path = fileURLToPath(new URL("../src/pages/dashboard.ts", import.meta.url));
  const source = await readFile(path, "utf8");
  const imports = new Map([...source.matchAll(/import\s*\{([\s\S]*?)\}\s*from\s*"([^"]+)"/g)].map((match) => [match[2], match[1].split(",").map(s => s.trim()).filter(s => s && !s.startsWith("type "))]));
  const result = await build({ stdin: { contents: source + "\nexport { wireActions };", resolveDir: fileURLToPath(new URL("../src/pages/", import.meta.url)), loader: "ts" }, bundle: true, platform: "node", format: "cjs", write: false,
    plugins: [{ name: "assignment-write-boundary", setup(builder) {
      builder.onResolve({ filter: /.*/ }, args => imports.has(args.path) && !["../lib/format", "../lib/icons"].includes(args.path) ? { path: args.path, namespace: "fixture" } : undefined);
      builder.onLoad({ filter: /.*/, namespace: "fixture" }, args => ({ contents: imports.get(args.path).map(binding => {
        const [original, alias = original] = binding.split(/\s+as\s+/);
        return `export const ${original} = (...args) => globalThis.__assignmentFeedback.${alias}?.(...args);`;
      }).join("\n"), loader: "js" }));
    } }] });
  const module = { exports: {} };
  new Function("module", "exports", "require", result.outputFiles[0].text)(module, module.exports, createRequire(import.meta.url));
  dashboard = module.exports;
});
after(() => { dashboard?.disposeDashboard(); delete globalThis.__assignmentFeedback; });

function formFixture(save) {
  let submit;
  let refreshes = 0;
  const writes = [];
  globalThis.__assignmentFeedback = { newId: () => "assignment-1", saveAssignment: async assignment => { writes.push(assignment); await save?.(); } };
  const button = { disabled: false, textContent: "Assign it" };
  const error = { innerHTML: "", replaceChildren() { this.innerHTML = ""; } };
  const status = { innerHTML: "", querySelector: () => null, replaceChildren() { this.innerHTML = ""; } };
  const values = { "a-title": "Float serve", "a-player": "player-1", "a-skill": "Serving", "a-sets": "3", "a-reps": "10" };
  const inputs = Object.fromEntries(Object.entries(values).map(([key, value]) => [key, { value }]));
  inputs["a-player"].selectedOptions = [{ textContent: "Alex <Team>" }];
  const form = { querySelector: () => button, addEventListener: (_event, handler) => { submit = handler; } };
  const root = { querySelectorAll: () => [], querySelector: selector => ({ "#assign-form": form, "#assign-error": error, "#action-status": status }[selector] ?? inputs[selector.slice(1)] ?? null) };
  dashboard.wireActions(root, "coach-1", () => { refreshes++; });
  return { button, error, status, writes, get refreshes() { return refreshes; }, submit: () => submit({ preventDefault() {} }) };
}

test("successful assignment confirms the saved drill and selected player", async () => {
  const f = formFixture();
  await f.submit();
  assert.match(f.status.innerHTML, /Float serve/);
  assert.match(f.status.innerHTML, /Alex &lt;Team&gt;/);
  assert.match(f.status.innerHTML, /Assigned/);
  assert.equal(f.refreshes, 1);
  assert.equal(f.writes[0].playerUid, "player-1");
  dashboard.disposeDashboard();
});

test("pending assignment shows progress and rejects a duplicate submit", async () => {
  let finish;
  const f = formFixture(() => new Promise(resolve => { finish = resolve; }));
  const pending = f.submit();
  assert.equal(f.button.textContent, "Assigning…");
  assert.equal(f.status.innerHTML, "");
  await f.submit();
  assert.equal(f.writes.length, 1);
  finish();
  await pending;
  dashboard.disposeDashboard();
});

test("failed assignment keeps the entries and restores the button without success", async (t) => {
  t.mock.method(console, "error", () => {});
  const f = formFixture(() => { throw new Error("offline"); });
  await f.submit();
  assert.match(f.error.innerHTML, /Could not assign/);
  assert.equal(f.button.disabled, false);
  assert.equal(f.button.textContent, "Assign it");
  assert.equal(f.status.innerHTML, "");
  assert.equal(f.refreshes, 0);
});
