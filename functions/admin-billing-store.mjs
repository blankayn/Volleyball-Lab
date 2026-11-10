export function adminBillingStore(db) {
  return {
    runTransaction: work => db.runTransaction(tx => work({
      getAll: async (...paths) => (await tx.getAll(...paths.map(path => db.doc(path)))).map(snapshot => snapshot.data()),
      async findLegacyOrder(uid, createdAt) {
        if (!Number.isSafeInteger(createdAt)) return;
        const query = db.collection("billingOrders").where("ownerUid", "==", uid)
          .where("createdAt", "==", createdAt).limit(1);
        const [match] = (await tx.get(query)).docs;
        if (match) return { orderId: match.id, order: match.data() };
      },
      set: (path, data) => tx.set(db.doc(path), data),
      update: (path, data) => tx.update(db.doc(path), data),
      delete: path => tx.delete(db.doc(path)),
    })),
  };
}
