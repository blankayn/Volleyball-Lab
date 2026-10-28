export const FIREBASE_PROJECT = "volleyball-lab";
const BASE = `https://firestore.googleapis.com/v1/projects/${FIREBASE_PROJECT}/databases/(default)/documents/`;
const MAX_REPS = 100;
const MAX_SOURCE_BYTES = 128 * 1024;
const TOO_LARGE = "This session is too large for an AI explanation. Try a shorter practice set.";

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

/** Claims are untrusted until a Firestore REST read verifies the bearer token. */
export function candidateIdentity(authorization, now) {
  const token = /^Bearer ([A-Za-z0-9_.-]{40,16384})$/.exec(authorization ?? "")?.[1];
  if (!token) throw new ApiError("UNAUTHENTICATED", "Sign in to read this session.");
  try {
    const parts = token.split(".");
    if (parts.length !== 3) throw Error();
    const decode = part => JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(part.replace(/-/g, "+").replace(/_/g, "/")), char => char.charCodeAt(0))));
    const header = decode(parts[0]); const claims = decode(parts[1]);
    if (header.alg !== "RS256" || typeof header.kid !== "string" || !header.kid || !/^[A-Za-z0-9_-]{1,128}$/.test(claims.sub ?? "") ||
      claims.aud !== FIREBASE_PROJECT || claims.iss !== `https://securetoken.google.com/${FIREBASE_PROJECT}` ||
      !Number.isInteger(claims.exp) || claims.exp * 1000 <= now || !Number.isInteger(claims.iat) || claims.iat * 1000 > now + 30_000 ||
      (claims.user_id !== undefined && claims.user_id !== claims.sub)) throw Error();
    return { uid: claims.sub, token };
  } catch { throw new ApiError("UNAUTHENTICATED", "Sign in again to read this session."); }
}

const numeric = value => value && "integerValue" in value ? Number(value.integerValue)
  : value && "doubleValue" in value ? Number(value.doubleValue) : null;

// Project only evidence inputs. Saved reps also contain timings and other
// metadata; constructing every map/field consumes the Free worker CPU budget.
function decodeSession(fields) {
  const repArray = fields.reps?.arrayValue;
  const reps = repArray ? repArray.values ?? [] : undefined;
  return {
    ownerUid: fields.ownerUid?.stringValue,
    sharedWith: (fields.sharedWith?.arrayValue?.values ?? []).map(value => value?.stringValue),
    skill: fields.skill?.stringValue,
    repCount: numeric(fields.repCount),
    formScore: numeric(fields.formScore),
    reps: Array.isArray(reps) ? reps.map(rep => ({ cueScores: (rep?.mapValue?.fields?.cueScores?.arrayValue?.values ?? []).map(raw => {
      const cue = raw?.mapValue?.fields;
      return { cueId: cue?.cueId?.stringValue, measured: numeric(cue?.measured), score: numeric(cue?.score) };
    }) })) : undefined,
  };
}

async function boundedJson(response, maximum) {
  if (Number(response.headers.get("content-length")) > maximum) throw new ApiError("FAILED_PRECONDITION", TOO_LARGE);
  const reader = response.body?.getReader();
  if (!reader) throw new ApiError("UNAVAILABLE", "Session access is unavailable.");
  const chunks = []; let length = 0;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    length += value.byteLength;
    if (length > maximum) { await reader.cancel(); throw new ApiError("FAILED_PRECONDITION", TOO_LARGE); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(length); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return JSON.parse(new TextDecoder().decode(bytes)); }
  catch { throw new ApiError("UNAVAILABLE", "Session access is unavailable."); }
}

async function firestoreRead(path, token, fetchImpl, mask = [], maximum = 16 * 1024) {
  const url = new URL(BASE + path);
  // Google's default pretty-printed REST payload adds substantial whitespace
  // to nested rep arrays. Compact JSON keeps the byte/CPU bound meaningful.
  url.searchParams.set("prettyPrint", "false");
  mask.forEach(field => url.searchParams.append("mask.fieldPaths", field));
  // Workers supports manual/follow, but rejects redirect:"error". Manual mode
  // also keeps the Firebase token on this fixed origin; 3xx responses fail below.
  const response = await fetchImpl(url.toString(), { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(4_000), redirect: "manual" });
  if (response.status === 401) throw new ApiError("UNAUTHENTICATED", "Sign in again to read this session.");
  if (response.status === 403) throw new ApiError("PERMISSION_DENIED", "You cannot access this session.");
  if (response.status !== 404 && !response.ok) throw new ApiError("UNAVAILABLE", "Session access is unavailable. Try again shortly.");
  const body = await boundedJson(response, maximum);
  if (response.status === 404 && body.error?.status !== "NOT_FOUND") throw new ApiError("UNAVAILABLE", "Session access is unavailable.");
  return { exists: response.status !== 404, body };
}

export async function authorizedSession(sessionId, identity, fetchImpl = fetch) {
  // The virtual gate's allow-get rule enforces caller/owner deletion fences,
  // owner OR explicit share+COACH role+Active roster. It is never client writable.
  // Its authorized 404 is expected: the gate is a rule, not a stored document.
  const metadataFields = ["ownerUid", "sharedWith", "skill", "repCount", "formScore"];
  const [metadata] = await Promise.all([
    firestoreRead(`sessions/${sessionId}`, identity.token, fetchImpl, metadataFields),
    firestoreRead(`aiSessionAccess/${sessionId}`, identity.token, fetchImpl),
  ]);
  if (!metadata.exists) throw new ApiError("NOT_FOUND", "This saved session is unavailable.");
  const count = numeric(metadata.body.fields?.repCount);
  if (count > MAX_REPS) throw new ApiError("FAILED_PRECONDITION", TOO_LARGE);
  if (!Number.isInteger(count) || count < 0) throw new ApiError("FAILED_PRECONDITION", "This session has incomplete measurements.");
  // Read the bounded metadata first so a 1000-rep legacy session is rejected
  // before transferring/parsing its potentially megabyte-sized measurement JSON.
  const source = await firestoreRead(`sessions/${sessionId}`, identity.token, fetchImpl, [...metadataFields, "reps"], MAX_SOURCE_BYTES);
  if (!source.exists) throw new ApiError("NOT_FOUND", "This saved session is unavailable.");
  const rawReps = source.body.fields?.reps?.arrayValue?.values;
  if (Array.isArray(rawReps) && rawReps.length > MAX_REPS) throw new ApiError("FAILED_PRECONDITION", TOO_LARGE);
  const session = decodeSession(source.body.fields ?? {});
  if (typeof session.ownerUid !== "string" || !/^[A-Za-z0-9_-]{1,128}$/.test(session.ownerUid) ||
    (session.ownerUid !== identity.uid && !session.sharedWith?.includes(identity.uid))) throw new ApiError("PERMISSION_DENIED", "You cannot access this session.");
  if (!Array.isArray(session.reps) || session.repCount !== session.reps.length) throw new ApiError("FAILED_PRECONDITION", "This session has incomplete measurements.");
  return session;
}
