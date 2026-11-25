import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { build } from "esbuild";

let directory, trust;
const here = dirname(fileURLToPath(import.meta.url));
before(async () => {
  directory = await mkdtemp(join(tmpdir(), "volleyball-trust-"));
  const stub = join(directory, "stub.cjs");
  await writeFile(stub, `const g=globalThis; module.exports={db:{},auth:{},doc:(_d,...p)=>p.join("/"),collection:(_d,p)=>p,query:(p)=>p,where:()=>0,limit:()=>0,serverTimestamp:()=>"now",
    getDoc:async p=>({data:()=>g.__trust.docs[p]}),getDocs:async()=>({docs:[]}),deleteDoc:async()=>{},setDoc:async(p,d)=>{g.__trust.writes.push([p,d]);if(g.__trust.failFirst){g.__trust.failFirst=false;g.__trust.writes.pop();throw new Error("denied");}},
    sendEmailVerification:async()=>{},verifyBeforeUpdateEmail:async()=>{}};`);
  const output = join(directory, "trust.cjs");
  await build({ entryPoints: [join(here, "../src/lib/coach-trust.ts")], bundle: true, platform: "node", format: "cjs", outfile: output, plugins: [{ name: "stub", setup(b) {
    b.onResolve({ filter: /^firebase\/|^\.\/firebase$/ }, () => ({ path: stub }));
  } }] });
  trust = (await import(pathToFileURL(output).href)).default;
});
after(async () => { delete globalThis.__trust; if (directory) await rm(directory, { recursive: true, force: true }); });

const reset = (docs = {}) => { globalThis.__trust = { docs, writes: [], failFirst: false }; };
const user = (email, verified) => ({ uid: "coach", email, emailVerified: verified, refreshed: 0, reload: async () => {}, async getIdToken() { this.refreshed++; } });
const view = (over = {}) => ({ record: { institutionDomain: "", confirmedAt: Date.now() }, vouches: 0, reviewers: 0, vouchedFor: [], coaches: [], ...over });
const status = (v, name = "Alex Rivera", org = "City Club") => trust.coachStatus(trust.coachChecks(v, name, org));

test("only restricted school and government endings count as an institution", () => {
  for (const ok of ["a@uc.edu.ph", "a@mail.school.edu", "a@deped.gov.ph", "a@x.ac.uk", "a@x.sch.id"]) assert.notEqual(trust.institutionDomain(ok), "", ok);
  for (const no of ["a@gmail.com", "a@edu.com", "a@uc.edu.ph.evil.com", "a@fakeedu.ph", "a@edu.ph.co", "", null]) assert.equal(trust.institutionDomain(no), "", String(no));
});
test("an unconfirmed email publishes nothing", async () => {
  reset();
  assert.equal(await trust.syncCoachTrust(user("a@uc.edu.ph", false)), null);
  assert.deepEqual(globalThis.__trust.writes, []);
});
test("a confirmed email publishes its domain once, and a refused token is refreshed", async () => {
  reset();
  globalThis.__trust.failFirst = true;
  const coach = user("A@UC.edu.ph", true);
  await trust.syncCoachTrust(coach);
  assert.deepEqual(globalThis.__trust.writes, [["coachTrust/coach", { institutionDomain: "uc.edu.ph", confirmedAt: "now" }]]);
  assert.equal(coach.refreshed, 1);
  reset({ "coachTrust/coach": { institutionDomain: "uc.edu.ph", confirmedAt: { toMillis: () => Date.now() } } });
  await trust.syncCoachTrust(user("a@uc.edu.ph", true));
  assert.deepEqual(globalThis.__trust.writes, []);
});
test("the same checks decide for every coach, with or without a school email", () => {
  assert.equal(status(view()), "none");
  assert.equal(status(view({ record: { institutionDomain: "uc.edu.ph", confirmedAt: Date.now() } })), "institution");
  assert.equal(status(view({ vouches: 2 })), "passed");
  assert.equal(status(view({ reviewers: 3 })), "passed");
  assert.equal(status(view({ vouches: 1, reviewers: 2 })), "none");
  assert.equal(status(view({ record: null, vouches: 5, reviewers: 9 })), "none");
  assert.equal(status(view({ vouches: 2 }), "Alex", "City Club"), "none");
  assert.equal(trust.coachStatus(trust.coachChecks(view({ record: null }), "Alex", ""), true), "team");
  assert.equal(status(view({ record: { institutionDomain: "uc.edu.ph", confirmedAt: Date.now() - 366 * 864e5 }, vouches: 2 })), "none");
});
