import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { initializeApp, deleteApp } from 'firebase-admin/app';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { removeAccountData } from '../account-cleanup.mjs';
const app = initializeApp({ projectId: 'demo-volleyball-account-cleanup' }, 'account-cleanup-test');
const db = getFirestore(app);
after(async () => { await db.terminate(); await deleteApp(app); });

test('deletion removes owner data and relationships, keeps other accounts, and is idempotent', async () => {
  const uid = 'delete-owner';
  const records = {
    'profiles/delete-owner': { name: 'Private' }, 'users/delete-owner': { role: 'PLAYER' },
    'coaches/delete-owner': { name: 'Coach' }, 'coachDirectory/delete-owner': { name: 'Coach' },
    'sessions/own': { ownerUid: uid, sharedWith: ['other'] },
    'sessions/other': { ownerUid: 'other', sharedWith: [uid, 'retained-coach'] },
    'recordings/own': { ownerUid: uid }, 'recordings/other': { ownerUid: 'other' },
    'assignments/a': { playerUid: uid, coachUid: 'other' },
    'submissions/s': { playerUid: 'other', coachUid: uid },
    'notes/n': { playerUid: uid, coachUid: 'other' },
    'roster/delete-owner/members/other': { playerUid: 'other' },
    'roster/other/members/delete-owner': { playerUid: uid },
    'roster/other/members/survivor': { playerUid: 'survivor' },
    'billingOrders/order': { ownerUid: uid }, 'billingPayments/payment': { orderId: 'order' },
    'billingSessions/checkout': { orderId: 'order' }, 'entitlements/delete-owner': { ownerUid: uid },
    'billingPending/delete-owner': { ownerUid: uid }, 'profiles/other': { name: 'Keep me' },
  };
  await Promise.all(Object.entries(records).map(([path, fields]) => db.doc(path).set(fields)));
  const events = [];
  const services = { db, fieldValue: FieldValue,
    bucket: { async deleteFiles(options) { assert.equal(options.prefix, `recordings/${uid}/`); events.push('storage'); } },
    auth: { async deleteUser(target) { assert.equal(target, uid); events.push('auth');
      assert.equal((await db.doc('profiles/delete-owner').get()).exists, false); } } };
  await removeAccountData(services, uid);
  assert.deepEqual(events, ['storage', 'auth']);
  assert.equal((await db.doc('sessions/other').get()).data().ownerUid, 'other');
  assert.deepEqual((await db.doc('sessions/other').get()).data().sharedWith, ['retained-coach']);
  for (const path of Object.keys(records)) {
    const keep = ['sessions/other', 'recordings/other', 'roster/other/members/survivor', 'profiles/other'].includes(path);
    assert.equal((await db.doc(path).get()).exists, keep, path);
  }
  assert.equal((await db.doc(`accountDeletions/${uid}`).get()).data().status, 'deleted');
  await removeAccountData(services, uid);
  assert.equal(events.length, 4);
});

test('storage failure leaves auth intact and deletion resumes on retry', async () => {
  const uid = 'retry-owner';
  await db.doc(`profiles/${uid}`).set({ name: 'Pending' });
  let failStorage = true; let deletedAuth = false;
  const services = { db, fieldValue: FieldValue,
    bucket: { async deleteFiles() { if (failStorage) throw new Error('offline'); } },
    auth: { async deleteUser() { deletedAuth = true; } } };
  await assert.rejects(removeAccountData(services, uid), /offline/);
  assert.equal(deletedAuth, false);
  assert.equal((await db.doc(`accountDeletions/${uid}`).get()).data().status, 'deleting');
  failStorage = false;
  await removeAccountData(services, uid);
  assert.equal(deletedAuth, true);
});
