const state = () => globalThis.__volleyballProfileSaveCheck;
const reference = (_db, ...parts) => ({ path: parts.join("/") });
const denial = () => Object.assign(new Error("Missing or insufficient permissions."), { code: "permission-denied" });
const optionalWrite = async (ref, data) => {
  if (ref.path.includes("/webSettings/") && state().denySettings) throw denial();
  if (ref.path.startsWith("users/") && state().denyAccountName) throw denial();
  state().writes.push({ path: ref.path, data });
};
module.exports = {
  db: {}, doc: reference,
  writeBatch() {
    const operations = [];
    return {
      set(ref, data) { operations.push({ path: ref.path, data }); },
      update(ref, data) { operations.push({ path: ref.path, data }); },
      async commit() {
        if (state().denyProfile || operations.some(write => write.path.includes("/webSettings/") && state().denySettings) || operations.some(write => write.path.startsWith("users/") && state().denyAccountName)) throw denial();
        state().writes.push(...operations);
      },
    };
  },
  setDoc: optionalWrite, updateDoc: optionalWrite,
  collection() {}, deleteDoc() {}, getDoc() {}, getDocs() {}, onSnapshot() {}, query() {}, serverTimestamp() {}, where() {},
};
