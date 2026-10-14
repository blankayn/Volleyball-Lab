import test, { before, after, beforeEach } from 'node:test';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, updateDoc } from 'firebase/firestore';
import { ref, uploadBytes, getBytes, listAll } from 'firebase/storage';
let env;
before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-volleyball-lab',
    firestore: { host: '127.0.0.1', port: 8087, rules: await readFile(new URL('../../firestore.rules', import.meta.url), 'utf8') },
    storage: { host: '127.0.0.1', port: 9199, rules: await readFile(new URL('../../storage.rules', import.meta.url), 'utf8') } });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore(); await env.clearStorage();
  await env.withSecurityRulesDisabled(async ctx => {
    await setDoc(doc(ctx.firestore(), 'sessions/s1'), { ownerUid: 'player', sharedWith: ['coach'] });
  });
});
const store = uid => uid ? env.authenticatedContext(uid).storage() : env.unauthenticatedContext().storage();
const video = uid => ref(store(uid), 'recordings/player/s1/video.mp4');
const bytes = new Uint8Array([1, 2, 3]);
test('video and feedback are private to the player and explicitly shared coach', async () => {
  await assertSucceeds(uploadBytes(video('player'), bytes, { contentType: 'video/mp4' }));
  await assertSucceeds(uploadBytes(ref(store('player'), 'recordings/player/s1/review.json'), bytes, { contentType: 'application/json' }));
  await assertSucceeds(getBytes(video('player')));
  await assertSucceeds(getBytes(video('coach')));
  for (const uid of ['other', null]) {
    await assertFails(getBytes(video(uid)));
    await assertFails(uploadBytes(video(uid), bytes, { contentType: 'video/mp4' }));
  }
  await assertFails(uploadBytes(video('coach'), bytes, { contentType: 'video/mp4' }));
  await assertFails(listAll(ref(store('other'), 'recordings/player')));
});
test('unowned paths, unknown files, invalid types and oversized review uploads fail', async () => {
  await assertFails(uploadBytes(ref(store('player'), 'recordings/other/s1/video.mp4'), bytes, { contentType: 'video/mp4' }));
  await assertFails(uploadBytes(ref(store('player'), 'recordings/player/missing/video.mp4'), bytes, { contentType: 'video/mp4' }));
  await assertFails(uploadBytes(ref(store('player'), 'recordings/player/s1/private.txt'), bytes, { contentType: 'text/plain' }));
  await assertFails(uploadBytes(video('player'), bytes, { contentType: 'image/jpeg' }));
  await assertFails(uploadBytes(ref(store('player'), 'recordings/player/s1/review.json'), new Uint8Array(10485761), { contentType: 'application/json' }));
});
test('deletion fence and removal from sharedWith revoke playback', async () => {
  await assertSucceeds(uploadBytes(video('player'), bytes, { contentType: 'video/mp4' }));
  await env.withSecurityRulesDisabled(async ctx => {
    await updateDoc(doc(ctx.firestore(), 'sessions/s1'), { sharedWith: [] });
  });
  await assertFails(getBytes(video('coach')));
  await env.withSecurityRulesDisabled(async ctx => {
    await setDoc(doc(ctx.firestore(), 'accountDeletions/player'), { status: 'deleting' });
  });
  await assertFails(getBytes(video('player')));
  await assertFails(uploadBytes(video('player'), bytes, { contentType: 'video/mp4' }));
});
