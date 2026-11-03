import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, updateDoc, collection, getDocs } from 'firebase/firestore';
let env;
const jpeg = 'data:image/jpeg;base64,/9j/AAAA';
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-profile-media', firestore: { rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') } }); });
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async ctx => {
    const db = ctx.firestore();
    for (const uid of ['player', 'other']) await setDoc(doc(db, 'users', uid), { role: 'PLAYER' });
    await setDoc(doc(db, 'users/coach'), { role: 'COACH' });
    await setDoc(doc(db, 'coachDirectory/coach'), { name: 'Coach' });
    await setDoc(doc(db, 'roster/coach/members/player'), { playerUid: 'player', status: 'Active' });
  });
});
const db = uid => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const media = (viewer, owner = 'player') => doc(db(viewer), 'profileMedia', owner);
test('student photo and banner are available only to their owner and connected coach', async () => {
  await assertSucceeds(setDoc(media('player'), { photo: jpeg, banner: jpeg, updatedAt: 1 }));
  await assertSucceeds(getDoc(media('coach')));
  await assertFails(getDoc(media('other')));
  await assertFails(getDoc(media(null)));
  await assertFails(getDocs(collection(db('coach'), 'profileMedia')));
  await assertFails(updateDoc(media('coach'), { photo: '' }));
  await env.withSecurityRulesDisabled(async ctx => { await updateDoc(doc(ctx.firestore(), 'roster/coach/members/player'), { status: 'Ended' }); });
  await assertFails(getDoc(media('coach')));
});
test('coach media is visible with the directory, while updates remain owner-only', async () => {
  await assertSucceeds(setDoc(media('coach', 'coach'), { photo: jpeg, banner: jpeg, updatedAt: 1 }));
  await assertSucceeds(getDoc(media('player', 'coach')));
  await assertFails(updateDoc(media('player', 'coach'), { banner: '' }));
  await assertSucceeds(updateDoc(media('coach', 'coach'), { photo: '', updatedAt: 2 }));
  assert.equal((await getDoc(media('player', 'coach'))).data().banner, jpeg);
});
test('untrusted formats, oversized images and private settings cannot enter shared media', async () => {
  for (const data of [{ photo: 'https://example.com/a.jpg' }, { photo: 'data:image/svg+xml;base64,AAAA' }, { photo: jpeg + 'A'.repeat(120000) }, { banner: jpeg + 'A'.repeat(300000) }, { photo: jpeg, certifications: 'private' }])
    await assertFails(setDoc(media('player'), { ...data, updatedAt: 1 }));
  await assertSucceeds(setDoc(media('player'), { banner: jpeg, updatedAt: 1 }));
  await assertSucceeds(setDoc(media('player'), { photo: jpeg, updatedAt: 2 }, { merge: true }));
  assert.equal((await getDoc(media('coach'))).data().banner, jpeg);
  await env.withSecurityRulesDisabled(async ctx => { await setDoc(doc(ctx.firestore(), 'accountDeletions/player'), { status: 'deleting' }); });
  await assertFails(getDoc(media('coach')));
  await assertFails(updateDoc(media('player'), { photo: '' }));
});
