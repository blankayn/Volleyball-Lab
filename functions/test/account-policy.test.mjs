import test from 'node:test';
import assert from 'node:assert/strict';
import { validateDeletion } from '../account-cleanup.mjs';
test('deletion needs a verified identity, exact confirmation, and recent authentication', () => {
  const request = { auth: { uid: 'owner', token: { auth_time: 1000 } }, data: { confirmation: 'DELETE', expectedUid: 'owner' } };
  assert.equal(validateDeletion(request, 1100), null);
  assert.equal(validateDeletion({ ...request, auth: null }, 1100), 'unauthenticated');
  assert.equal(validateDeletion({ ...request, data: { confirmation: 'delete' } }, 1100), 'invalid-argument');
  assert.equal(validateDeletion({ ...request, data: { confirmation: 'DELETE', uid: 'victim' } }, 1100), 'invalid-argument');
  assert.equal(validateDeletion({ ...request, data: { confirmation: 'DELETE', expectedUid: 'victim' } }, 1100), 'invalid-argument');
  assert.equal(validateDeletion(request, 1301), 'failed-precondition');
  assert.equal(validateDeletion({ ...request, auth: { uid: 'owner', token: {} } }, 1100), 'failed-precondition');
  assert.equal(validateDeletion(request, 900), 'failed-precondition');
});
