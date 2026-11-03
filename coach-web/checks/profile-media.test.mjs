import test, { before, beforeEach, afterEach, after } from "node:test";
import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";
let media;
const auth = { currentUser: { uid: "coach" } };
const original = { document: globalThis.document, observer: globalThis.MutationObserver };
before(async () => {
  globalThis.__profileMediaCheck = { auth };
  const result = await build({ entryPoints: [fileURLToPath(new URL('../src/lib/profile-media.ts', import.meta.url))], bundle: true, write: false, platform: 'node', format: 'cjs', plugins: [{ name: 'media-boundary', setup(builder) {
    builder.onResolve({ filter: /^firebase\/auth$|^firebase\/firestore$|^\.\/firebase$/ }, args => ({ path: args.path, namespace: 'fixture' }));
    builder.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ loader: 'js', contents: `
      export const auth = globalThis.__profileMediaCheck.auth; export const db = {};
      export function doc(_db, ...parts) { return parts.join('/'); }
      export function onSnapshot(path, next, error) { globalThis.__profileMediaCheck.next = next; globalThis.__profileMediaCheck.error = error; return () => { globalThis.__profileMediaCheck.stopped = true; }; }
      export function onAuthStateChanged(_auth, next) { globalThis.__profileMediaCheck.authChanged = next; return () => {}; }
    ` }));
  } }] });
  const module = { exports: {} }; new Function('module', 'exports', 'require', result.outputFiles[0].text)(module, module.exports, createRequire(import.meta.url)); media = module.exports;
});
beforeEach(() => {
  auth.currentUser = { uid: 'coach' }; globalThis.__profileMediaCheck = { auth };
  globalThis.MutationObserver = class { observe() {} disconnect() {} };
  globalThis.document = { createElement() { return { style: {}, addEventListener() {}, remove() { if (this.parentElement) this.parentElement.child = null; } }; } };
});
afterEach(() => { globalThis.document = original.document; globalThis.MutationObserver = original.observer; });
after(() => { delete globalThis.__profileMediaCheck; });
function ui() {
  const avatar = { dataset: { profileAvatar: 'player' }, textContent: 'PA', child: null, isConnected: true,
    replaceChildren() { this.child = null; this.textContent = ''; }, append(img) { img.parentElement = this; this.child = img; } };
  const banner = { dataset: { profileBanner: 'player' }, style: { backgroundImage: '' }, hidden: true,
    hasAttribute: name => name === 'data-profile-optional-banner', querySelector: () => null };
  const root = { querySelectorAll(selector) {
    if (selector.includes(', ')) return [avatar, banner];
    if (selector.includes(' img')) return avatar.child ? [avatar.child] : [];
    return selector.includes('avatar') ? [avatar] : [banner];
  } };
  return { avatar, banner, root, emit: data => globalThis.__profileMediaCheck.next({ data: () => data }) };
}
const photo = 'data:image/jpeg;base64,/9j/AAAA';
test('student cards paint the actual shared photo and banner, then clear on revocation', () => {
  const f = ui(), dispose = media.wireProfileMedia(f.root);
  f.emit({ photo, banner: photo });
  assert.equal(f.avatar.child.src, photo); assert.equal(f.banner.hidden, false); assert.match(f.banner.style.backgroundImage, /data:image\/jpeg/);
  globalThis.__profileMediaCheck.error(new Error('permission-denied'));
  assert.equal(f.avatar.child, null); assert.equal(f.avatar.textContent, 'PA'); assert.equal(f.banner.hidden, true);
  dispose();
});
test('clearing a photo restores initials and never renders untrusted URLs or SVG data', () => {
  const f = ui(), dispose = media.wireProfileMedia(f.root);
  f.emit({ photo }); f.emit({ photo: '', banner: '' });
  assert.equal(f.avatar.child, null); assert.equal(f.avatar.textContent, 'PA');
  f.emit({ photo: 'https://untrusted.example/a.jpg', banner: 'data:image/svg+xml;base64,AAAA' });
  assert.equal(f.avatar.child, null); assert.equal(f.banner.hidden, true); dispose();
});
test('switching accounts immediately removes shared images and stops their listener', () => {
  const f = ui(), dispose = media.wireProfileMedia(f.root); f.emit({ photo, banner: photo });
  auth.currentUser = { uid: 'other' }; globalThis.__profileMediaCheck.authChanged(auth.currentUser);
  assert.equal(f.avatar.child, null); assert.equal(f.banner.style.backgroundImage, ''); assert.equal(globalThis.__profileMediaCheck.stopped, true);
  f.emit({ photo }); assert.equal(f.avatar.child, null); dispose();
});
