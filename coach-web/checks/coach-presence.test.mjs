import test, { before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from 'esbuild';

let directory, policy;
before(async () => {
  directory = await mkdtemp(join(tmpdir(), 'volleyball-presence-'));
  const output = join(directory, 'policy.mjs');
  await build({ entryPoints: [fileURLToPath(new URL('../src/lib/coach-presence-policy.ts', import.meta.url))], bundle: true, platform: 'node', format: 'esm', outfile: output });
  policy = await import(pathToFileURL(output).href);
});
after(async () => { if (directory) await rm(directory, { recursive: true, force: true }); });

const now = 1_800_000_000_000;
const lease = (status, updatedAt = now - 10_000, expiresAt = now + 90_000) => ({ status, updatedAt, expiresAt });
test('live device aggregation favors busy and ignores a device that went offline', () => {
  assert.equal(policy.resolveCoachPresence([lease('Online'), lease('Busy')], now), 'Busy');
  assert.equal(policy.resolveCoachPresence([lease('Online'), lease('Offline')], now), 'Online');
});
test('expiry, stale receipts, missing records and far-future timestamps resolve offline', () => {
  assert.equal(policy.resolveCoachPresence([lease('Busy', now, now + 1)], now), 'Busy');
  assert.equal(policy.resolveCoachPresence([lease('Busy', now, now + 1)], now + 1), 'Offline');
  assert.equal(policy.resolveCoachPresence([lease('Online', now - 120_000), lease('Busy', now + 60_000)], now), 'Offline');
  assert.equal(policy.resolveCoachPresence([], now), 'Offline');
});
test('inactivity, hidden pages and disconnection take priority over busy', () => {
  assert.equal(policy.localCoachPresence(true, true, true, 0), 'Busy');
  assert.equal(policy.localCoachPresence(true, true, false, 0), 'Online');
  assert.equal(policy.localCoachPresence(false, true, true, 0), 'Offline');
  assert.equal(policy.localCoachPresence(true, false, true, 0), 'Offline');
  assert.equal(policy.localCoachPresence(true, true, true, policy.PRESENCE_IDLE_MS), 'Offline');
});
function fixture(write) {
  let time = now;
  const inputs = { foreground: true, connected: true, busy: false };
  const writes = [];
  const publisher = new policy.CoachPresencePublisher(() => inputs, async (status, expiresAt) => { writes.push({ status, expiresAt }); await write?.(); }, () => time);
  return { publisher, inputs, writes, advance: ms => { time += ms; } };
}
test('activity does not spam writes; heartbeat and idle transitions are bounded', async () => {
  const f = fixture();
  await f.publisher.tick();
  for (let i = 0; i < 20; i++) f.publisher.touch();
  assert.equal(f.writes.length, 1);
  f.advance(60_000); await f.publisher.tick();
  assert.equal(f.writes.length, 2);
  f.advance(240_000); await f.publisher.tick();
  assert.equal(f.writes.at(-1).status, 'Offline');
  f.advance(120_000); await f.publisher.tick();
  assert.equal(f.writes.length, 3);
  f.publisher.stop();
});
test('failed writes retry after backoff and are not treated as successful heartbeats', async () => {
  let fail = true;
  const f = fixture(() => { if (fail) throw Error('offline'); });
  await f.publisher.tick(); await f.publisher.tick();
  assert.equal(f.writes.length, 1);
  fail = false; f.advance(15_000); await f.publisher.tick();
  assert.equal(f.writes.length, 2);
  assert.equal(f.writes[1].expiresAt, now + 15_000 + 120_000);
  f.publisher.stop();
});
test('a busy transition during an in-flight heartbeat is sent after it completes', async () => {
  let release;
  const gate = new Promise(resolve => { release = resolve; });
  let attempts = 0;
  const f = fixture(() => ++attempts === 1 ? gate : undefined);
  const first = f.publisher.tick();
  f.inputs.busy = true; f.publisher.touch();
  release(); await first; await Promise.resolve();
  assert.deepEqual(f.writes.map(row => row.status), ['Online', 'Busy']);
  f.publisher.stop();
});
test('disposing a pending connection cannot restart its heartbeat loop', async () => {
  let release;
  const f = fixture(() => new Promise(resolve => { release = resolve; }));
  const first = f.publisher.tick();
  f.publisher.stop(); release(); await first;
  f.advance(120_000); f.publisher.touch(); await f.publisher.tick();
  assert.equal(f.writes.length, 1);
});
