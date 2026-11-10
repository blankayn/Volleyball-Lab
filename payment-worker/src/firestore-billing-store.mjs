function encode(value) {
  if (typeof value === "string") return { stringValue: value };
  if (typeof value === "boolean") return { booleanValue: value };
  if (Number.isSafeInteger(value)) return { integerValue: String(value) };
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  throw new Error("unsupported_billing_value");
}

function decode(document) {
  return Object.fromEntries(Object.entries(document.fields ?? {}).map(([field, value]) => {
    const scalar = value.stringValue ?? value.booleanValue ??
      (value.integerValue === undefined ? undefined : Number(value.integerValue));
    return [field, scalar];
  }));
}

function documentWrite(name, data, update) {
  const fields = Object.fromEntries(Object.entries(data).map(([field, value]) => [field, encode(value)]));
  const write = { update: { name, fields } };
  if (update) {
    write.updateMask = { fieldPaths: Object.keys(fields) };
    write.currentDocument = { exists: true };
  }
  return write;
}

export function firestoreBillingStore({ projectId, token, fetcher = fetch, origin = "https://firestore.googleapis.com" }) {
  const database = `projects/${projectId}/databases/(default)`;
  const documentName = path => `${database}/documents/${path}`;
  async function request(operation, body) {
    const response = await fetcher(`${origin}/v1/${database}/documents:${operation}`, {
      method: "POST",
      redirect: "manual",
      signal: AbortSignal.timeout(6_000),
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const result = await response.json();
    if (!response.ok) {
      const error = new Error(`firestore_${operation}_${response.status}`);
      error.conflict = result.error?.status === "ABORTED";
      throw error;
    }
    return result;
  }

  return {
    async runTransaction(work) {
      // One retry leaves room under the Workers Free external request limit.
      for (let attempt = 0; ; attempt++) {
        const { transaction } = await request("beginTransaction", {});
        const writes = [];
        const rollback = () => request("rollback", { transaction });
        try {
          const result = await work({
            async getAll(...paths) {
              const documents = paths.map(documentName);
              const snapshots = await request("batchGet", { documents, transaction });
              const results = new Map(snapshots.map(snapshot => snapshot.found
                ? [snapshot.found.name, decode(snapshot.found)]
                : [snapshot.missing, undefined]));
              if (!documents.every(name => results.has(name))) throw new Error("incomplete_billing_read");
              return documents.map(name => results.get(name));
            },
            async findLegacyOrder(uid, createdAt) {
              if (!Number.isSafeInteger(createdAt)) return;
              const filters = Object.entries({ ownerUid: uid, createdAt }).map(([fieldPath, value]) => ({
                fieldFilter: { field: { fieldPath }, op: "EQUAL", value: encode(value) },
              }));
              const matches = await request("runQuery", {
                transaction,
                structuredQuery: {
                  from: [{ collectionId: "billingOrders" }],
                  where: { compositeFilter: { op: "AND", filters } },
                  limit: 1,
                },
              });
              const document = matches.find(match => match.document)?.document;
              if (document) return { orderId: document.name.split("/").at(-1), order: decode(document) };
            },
            set: (path, data) => writes.push(documentWrite(documentName(path), data, false)),
            update: (path, data) => writes.push(documentWrite(documentName(path), data, true)),
            delete: path => writes.push({ delete: documentName(path) }),
          });
          if (writes.length) await request("commit", { transaction, writes });
          else await rollback();
          return result;
        } catch (error) {
          await rollback().catch(() => {});
          if (!error.conflict || attempt > 0) throw error;
        }
      }
    },
  };
}
