import test, { before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { build } from "esbuild";

let directory, marks;
const here = dirname(fileURLToPath(import.meta.url));
before(async () => {
  directory = await mkdtemp(join(tmpdir(), "volleyball-marks-"));
  const stub = join(directory, "stub.cjs");
  await writeFile(stub, `const g=globalThis; const has=p=>p in g.__m.docs;
    module.exports={db:{},auth:{},doc:(_d,...p)=>p.join("/"),collection:(_d,p)=>p,query:(p)=>p,where:()=>0,limit:()=>0,serverTimestamp:()=>"now",
    getDoc:async p=>({exists:()=>has(p),data:()=>g.__m.docs[p]}),
    getDocs:async p=>({docs:Object.entries(g.__m.docs).filter(([k])=>k.startsWith(p+"/")).map(([k,d])=>({id:k.split("/")[1],data:()=>d}))}),
    setDoc:async(p,d)=>{g.__m.docs[p]=d},deleteDoc:async p=>{delete g.__m.docs[p]},onSnapshot(){},writeBatch(){},updateDoc(){},
    sendEmailVerification(){},verifyBeforeUpdateEmail(){}};`);
  const output = join(directory, "marks.cjs");
  await build({ entryPoints: [join(here, "../src/lib/rep-marks.ts")], bundle: true, platform: "node", format: "cjs", outfile: output, plugins: [{ name: "stub", setup(b) {
    b.onResolve({ filter: /^firebase\/|^\.\/firebase$/ }, () => ({ path: stub }));
  } }] });
  marks = (await import(pathToFileURL(output).href)).default;
});
after(async () => { delete globalThis.__m; if (directory) await rm(directory, { recursive: true, force: true }); });

const base = () => ({
  "entitlements/coach": { status: "active", expiresAtEpochMs: 0 }, "sessions/s1": { ownerUid: "p1", skill: "Receiving", repCount: 3 },
  "coachVerified/coach": { verified: true },
  "coaches/coach": { name: "Alex Rivera", organisation: "Club" },
});
const load = (docs) => { globalThis.__m = { docs }; return marks.loadMarkContext("s1", "coach"); };

test("reps can be marked only by a verified coach", async () => {
  const { "coachVerified/coach": _b, ...unverified } = base();
  assert.equal((await load(unverified)).state, "unverified");
  const ready = await load(base());
  assert.equal(ready.state, "ready");
  assert.match(marks.markHtml(ready, 1), /data-mark="Right"/);
  assert.equal(marks.markHtml(ready, 4), "", "a rep that is not in the saved session");
  assert.doesNotMatch(marks.markHtml({ ...ready, state: "unverified" }, 1), /data-mark=/);
});
test("a mark is saved with the fields the rules expect, reopened, and undone", async () => {
  const context = await load(base());
  await marks.saveMark(context, 2, { verdict: "Wrong", cueIds: ["PassKneeFlexion", "NotACue", "ServeReachHeight"] });
  assert.deepEqual(globalThis.__m.docs["repLabels/s1_2_coach"], { sessionId: "s1", playerUid: "p1", coachUid: "coach",
    skill: "Receiving", repNumber: 2, verdict: "Wrong", cueIds: ["PassKneeFlexion"], createdAt: "now" });
  const again = await marks.loadMarkContext("s1", "coach");
  assert.deepEqual(again.marks.get(2), { verdict: "Wrong", cueIds: ["PassKneeFlexion"] });
  assert.match(marks.markHtml(again, 2), /data-mark-cue="PassKneeFlexion" aria-pressed="true"/);
  await marks.saveMark(again, 2, { verdict: "Right", cueIds: ["PassKneeFlexion"] });
  assert.deepEqual(globalThis.__m.docs["repLabels/s1_2_coach"].cueIds, []);
  await marks.saveMark(again, 2, null);
  assert.equal("repLabels/s1_2_coach" in globalThis.__m.docs, false);
});
test("jump height is never offered as something to mark", () => {
  assert.ok(marks.markableCues("Spiking").every((c) => c.id !== "SpikeJumpHeight"));
});
