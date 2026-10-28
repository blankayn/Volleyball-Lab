import test, { before, after, beforeEach } from 'node:test';
import { readFile } from 'node:fs/promises';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc } from 'firebase/firestore';

let env;
before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-volleyball-portraits', firestore: {
  rules: await readFile(process.env.FIRESTORE_RULES_FILE || new URL('../../firestore.rules', import.meta.url), 'utf8') } }); });
after(async () => { await env?.cleanup(); });
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), 'coaches/coach'), { name: 'Coach', licenceNumber: 'private' });
    await setDoc(doc(context.firestore(), 'coachDirectory/coach'), { name: 'Coach' });
  });
});
const db = uid => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const photo = () => ({ photo: 'data:image/jpeg;base64,/9j/2Q==', updatedAt: Date.now() });

test('listed coach publishes a portrait; authenticated students can read only the portrait', async () => {
  await assertSucceeds(setDoc(doc(db('coach'), 'coachPhotos/coach'), photo()));
  await assertSucceeds(getDoc(doc(db('student'), 'coachPhotos/coach')));
  await assertFails(getDoc(doc(db(null), 'coachPhotos/coach')));
  await assertFails(getDoc(doc(db('student'), 'coaches/coach')));
  await assertFails(getDoc(doc(db('student'), 'coaches/coach/webSettings/preferences')));
});

test('other users and unlisted students cannot publish or remove a coach portrait', async () => {
  await assertFails(setDoc(doc(db('student'), 'coachPhotos/coach'), photo()));
  await assertFails(setDoc(doc(db('student'), 'coachPhotos/student'), photo()));
  await assertSucceeds(setDoc(doc(db('coach'), 'coachPhotos/coach'), photo()));
  await assertFails(deleteDoc(doc(db('student'), 'coachPhotos/coach')));
  await assertSucceeds(deleteDoc(doc(db('coach'), 'coachPhotos/coach')));
});

test('portrait accepts removal but rejects arbitrary content, private fields and oversized data', async () => {
  const ref = doc(db('coach'), 'coachPhotos/coach');
  for (const bad of [
    { photo: 'https://example.com/tracking.jpg' }, { photo: 'data:image/svg+xml;base64,PHN2Zz4=' },
    { photo: 'data:image/jpeg;base64,' + 'A'.repeat(120000) }, { photo: 123 },
    { licenceNumber: 'private' }, { updatedAt: 'bad' },
  ]) await assertFails(setDoc(ref, { ...photo(), ...bad }));
  await assertSucceeds(setDoc(ref, photo()));
  await assertSucceeds(updateDoc(ref, { photo: '', updatedAt: Date.now() }));
});

test('a removed directory entry makes its orphaned portrait unreadable', async () => {
  await assertSucceeds(setDoc(doc(db('coach'), 'coachPhotos/coach'), photo()));
  await assertSucceeds(deleteDoc(doc(db('coach'), 'coachDirectory/coach')));
  await assertFails(getDoc(doc(db('student'), 'coachPhotos/coach')));
});
