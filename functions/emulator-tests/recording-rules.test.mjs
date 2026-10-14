import test, { before, after, beforeEach } from 'node:test';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc } from 'firebase/firestore';
let env;
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-volleyball-recordings', firestore: {
  rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') } }); });
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx => {
    await setDoc(doc(ctx.firestore(), 'sessions/s1'), { ownerUid: 'player', sharedWith: ['coach'] });
  });
});
const db = uid => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const ref = uid => doc(db(uid), 'recordings/s1');
const manifest = { ownerUid: 'player', sessionId: 's1', version: 1, videoBytes: 12, reviewBytes: 12, ready: true };
test('recording metadata stays with owner and shared coach; malformed and injected fields fail', async () => {
  await assertSucceeds(setDoc(ref('player'), manifest));
  await assertSucceeds(getDoc(ref('coach')));
  for (const uid of ['other', null]) await assertFails(getDoc(ref(uid)));
  await assertFails(updateDoc(ref('coach'), { ownerUid: 'coach' }));
  for (const patch of [{ ownerUid: 'other' }, { videoBytes: 104857601 }, { reviewBytes: 'huge' },
    { ready: false }, { downloadUrl: 'https://public.example/' }, { sessionId: 'wrong' }])
    await assertFails(setDoc(ref('player'), { ...manifest, ...patch }));
});
test('deletion fence is server-only and prevents late profile or recording writes', async () => {
  await assertFails(setDoc(doc(db('player'), 'accountDeletions/player'), { status: 'deleting' }));
  await env.withSecurityRulesDisabled(async ctx => {
    await setDoc(doc(ctx.firestore(), 'accountDeletions/player'), { status: 'deleting' });
  });
  await assertFails(setDoc(ref('player'), manifest));
  await assertFails(setDoc(doc(db('player'), 'profiles/player'), { name: 'Restore' }));
  await assertSucceeds(getDoc(doc(db('player'), 'accountDeletions/player')));
  await assertFails(getDoc(doc(db('other'), 'accountDeletions/player')));
});
