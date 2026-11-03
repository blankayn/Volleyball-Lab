import { ApiError, candidateIdentity, firestoreRead } from "./firebase.mjs";

// Below the Workers Free 100 MB request limit, including room for headers.
export const MAX_VIDEO_BYTES = 90 * 1024 * 1024;
export const MAX_REVIEW_BYTES = 10 * 1024 * 1024;
const files = { "video.mp4": ["video/mp4", MAX_VIDEO_BYTES], "review.json": ["application/json", MAX_REVIEW_BYTES] };
const statuses = { UNAUTHENTICATED: 401, PERMISSION_DENIED: 403, NOT_FOUND: 404, INVALID_ARGUMENT: 400, FAILED_PRECONDITION: 409, UNAVAILABLE: 503 };

export async function recordingRequest(request, env, headers, { fetchImpl = fetch, now = Date.now } = {}) {
  try {
    const match = /^\/recordings\/([A-Za-z0-9_-]{1,128})\/(video\.mp4|review\.json|complete)$/.exec(new URL(request.url).pathname);
    if (!match) throw new ApiError("NOT_FOUND", "Recording endpoint unavailable.");
    const [, sessionId, file] = match;
    const upload = request.method === "PUT" && file in files;
    const complete = request.method === "POST" && file === "complete";
    const download = ["GET", "HEAD"].includes(request.method) && file in files;
    if (!upload && !complete && !download) return new Response(null, { status: 405, headers: { ...headers, Allow: "GET, HEAD, PUT, POST, OPTIONS" } });
    const identity = candidateIdentity(request.headers.get("authorization"), now());
    // Google verifies the token. The existing virtual gate additionally checks
    // deletion fences, explicit sharing, COACH role and an Active roster link.
    const [row] = await Promise.all([
      firestoreRead(`sessions/${sessionId}`, identity.token, fetchImpl, ["ownerUid", "sharedWith"]),
      firestoreRead(`aiSessionAccess/${sessionId}`, identity.token, fetchImpl),
    ]);
    if (!row.exists) throw new ApiError("NOT_FOUND", "This saved session is unavailable.");
    const owner = row.body.fields?.ownerUid?.stringValue;
    const shared = row.body.fields?.sharedWith?.arrayValue?.values ?? [];
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(owner ?? "") ||
      (owner !== identity.uid && !shared.some(value => value.stringValue === identity.uid)) ||
      ((upload || complete) && owner !== identity.uid)) throw new ApiError("PERMISSION_DENIED", "You cannot access this recording.");
    if (!env.RECORDINGS) throw new ApiError("UNAVAILABLE", "Recording storage is being configured.");
    const bucket = env.RECORDINGS;
    const prefix = `recordings/${owner}/${sessionId}/`;
    if (upload) {
      const [type, maximum] = files[file];
      const size = Number(request.headers.get("content-length"));
      if (request.headers.get("content-type") !== type || !Number.isSafeInteger(size) || size < 1 || size > maximum || !request.body)
        throw new ApiError("INVALID_ARGUMENT", "The recording has an invalid type or size. Record a shorter session.");
      // R2 conditionals make completed objects immutable, including concurrent
      // retries. A lost response can be retried without replacing coach footage.
      const existing = await bucket.head(prefix + file);
      if (existing && existing.size !== size) throw new ApiError("FAILED_PRECONDITION", "This recording was already uploaded with different content.");
      if (!existing) {
        const saved = await bucket.put(prefix + file, request.body, { onlyIf: { etagDoesNotMatch: "*" }, httpMetadata: { contentType: type } });
        if (!saved) {
          const raced = await bucket.head(prefix + file);
          if (raced?.size !== size) throw new ApiError("FAILED_PRECONDITION", "Retry sending this recording.");
        }
      }
      return Response.json({ ok: true }, { headers });
    }
    if (complete) {
      const [video, review] = await Promise.all([bucket.head(prefix + "video.mp4"), bucket.head(prefix + "review.json")]);
      if (!video || !review || video.size < 1 || video.size > MAX_VIDEO_BYTES || review.size < 1 || review.size > MAX_REVIEW_BYTES)
        throw new ApiError("FAILED_PRECONDITION", "Both the video and pose feedback must finish uploading. Try sending again.");
      await bucket.put(prefix + "ready.json", JSON.stringify({ videoBytes: video.size, reviewBytes: review.size }), { httpMetadata: { contentType: "application/json" } });
      return Response.json({ ok: true }, { headers });
    }
    if (!await bucket.head(prefix + "ready.json")) throw new ApiError("NOT_FOUND", "No cloud recording was sent for this session.");
    const object = request.method === "HEAD" ? await bucket.head(prefix + file) : await bucket.get(prefix + file);
    if (!object) throw new ApiError("NOT_FOUND", "This recording is unavailable.");
    return new Response(request.method === "HEAD" ? null : object.body, { headers: { ...headers, "Content-Type": files[file][0], "Content-Length": String(object.size), "Cache-Control": "private, no-store" } });
  } catch (error) {
    const known = error instanceof ApiError;
    const status = known ? error.status : "UNAVAILABLE";
    return Response.json({ error: { status, message: known ? error.message : "Recording transfer failed. Please try again." } }, { status: statuses[status] ?? 503, headers });
  }
}
