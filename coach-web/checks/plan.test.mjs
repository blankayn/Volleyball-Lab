import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { build } from "esbuild";

let directory, plan, cloud;
const here = dirname(fileURLToPath(import.meta.url));
before(async () => {
  directory = await mkdtemp(join(tmpdir(), "volleyball-plan-"));
  const stub = join(directory, "stub.cjs");
  await writeFile(stub, `const g=globalThis; const snap=p=>({id:String(p).split("/").pop(),exists:()=>p in g.__p.docs,data:()=>g.__p.docs[p]});
    module.exports={db:{},auth:{},doc:(_d,...p)=>p.join("/"),collection:(_d,...p)=>p.join("/"),query:p=>p,where:()=>0,limit:()=>0,serverTimestamp:()=>"now",
    getDoc:async p=>{ if(g.__p.deny) throw new Error("denied"); return snap(p); },
    getDocs:async p=>({docs:Object.keys(g.__p.docs).filter(k=>k.startsWith(p+"/")).map(snap)}),
    setDoc:async(p,d,o)=>{g.__p.docs[p]=o?.merge?{...g.__p.docs[p],...d}:d},updateDoc:async(p,d)=>{g.__p.docs[p]={...g.__p.docs[p],...d}},deleteDoc(){},onSnapshot(){},
    writeBatch:()=>{const ops=[];return{set:(p,d)=>ops.push(["set",p,d]),update:(p,d)=>ops.push(["update",p,d]),commit:async()=>{g.__p.commits.push(ops);for(const[k,p,d]of ops)g.__p.docs[p]=k==="set"?d:{...g.__p.docs[p],...d}}}}};`);
  const bundle = async (entry) => {
    const output = join(directory, `${entry}.cjs`);
    await build({ entryPoints: [join(here, `../src/lib/${entry}.ts`)], bundle: true, platform: "node", format: "cjs", outfile: output, plugins: [{ name: "stub", setup(b) {
      b.onResolve({ filter: /^firebase\/|^\.\/firebase$/ }, () => ({ path: stub }));
    } }] });
    return (await import(pathToFileURL(output).href)).default;
  };
  plan = await bundle("plan"); cloud = await bundle("cloud");
});
after(async () => { delete globalThis.__p; if (directory) await rm(directory, { recursive: true, force: true }); });
const start = (docs = {}) => { globalThis.__p = { docs, commits: [], deny: false }; };

test("only an active, unexpired entitlement is Pro, and anything unreadable is Free", async () => {
  assert.equal(plan.planOf("u", undefined), false);
  assert.equal(plan.planOf("u", { status: "active", expiresAtEpochMs: 0 }), true);
  assert.equal(plan.planOf("u", { status: "active", expiresAtEpochMs: 10 }, 9), true);
  assert.equal(plan.planOf("u", { status: "active", expiresAtEpochMs: 10 }, 11), false);
  assert.equal(plan.planOf("u", { status: "active" }), false);
  assert.equal(plan.planOf("u", { status: "cancelled", expiresAtEpochMs: 0 }), false);
  start({ "entitlements/u": { status: "active", expiresAtEpochMs: 0 } });
  assert.equal(await plan.loadPlan("u"), true);
  assert.equal(plan.canAccept(50), true);
  globalThis.__p.deny = true;
  assert.equal(await plan.loadPlan("u"), false);
  assert.equal(plan.canAccept(2), true);
  assert.equal(plan.canAccept(3), false);
});
test("places follow the team", () => {
  assert.deepEqual(plan.nextSlots(null, ["a", "b"], true), ["a", "b"]);
  assert.equal(plan.nextSlots(null, ["a"], false), null);
  assert.deepEqual(plan.nextSlots(["a", "b"], ["b", "c"], true), ["b", "c"]);
  assert.deepEqual(plan.nextSlots(["a", "b"], ["b", "legacy"], false), ["b"], "ending never adds a place");
});
const member = (status) => ({ playerUid: "x", playerName: "x", status, requestedAt: 1 });
test("accepting a student writes their place and their status together", async () => {
  start({ "roster/coach/members/a": { ...member("Active"), playerUid: "a" }, "roster/coach/members/b": { ...member("Pending"), playerUid: "b" }, "coachSlots/coach": { players: ["a"], updatedAt: 1 } });
  await cloud.setMemberStatus("coach", "b", "Active");
  const [ops] = globalThis.__p.commits;
  assert.deepEqual(ops.map(([k, p]) => [k, p]), [["set", "coachSlots/coach"], ["update", "roster/coach/members/b"]]);
  assert.deepEqual(ops[0][2].players, ["a", "b"]);
  await cloud.setMemberStatus("coach", "a", "Ended");
  assert.deepEqual(globalThis.__p.docs["coachSlots/coach"].players, ["b"]);
  assert.equal(globalThis.__p.docs["coachDirectory/coach"].rosterSize, 1);
});
test("names are shown as first, middle initial and last", () => {
  assert.equal(cloud.displayName("Maria", "Cruz", "Santos"), "Maria C. Santos");
  assert.equal(cloud.displayName(" Maria ", "", " Santos "), "Maria Santos");
  assert.equal(cloud.displayName("x".repeat(40), "Cruz", "y".repeat(40)).length, 60);
});
