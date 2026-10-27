# Session AI on Cloudflare Workers Free

This service replaces only the saved-session AI callable. Firebase Auth and
Firestore remain on Spark. It accepts the Firebase callable JSON protocol at
`/explainTrainingSession`, so Android/web Firebase Functions SDKs can use a custom
URL while automatically sending their Firebase ID token.

## Required access gate

Deploy the `aiSessionAccess/{sessionId}` virtual allow-get rule before activation.
The rule checks both deletion fences and requires the owner, or an explicitly
shared COACH with an Active roster connection. No gate document is stored; an
authorized REST read returns `404 NOT_FOUND`. Missing rules fail closed with 403.
The Worker also requires a successful source session read using the same token.
Google verifies the ID token and existing rules; the Worker has no Firebase
administrator credential and never accepts client-provided scores.

## Deployment

1. Install the pinned tooling with `npm install` in this directory.
2. Create D1 database `volleyball-lab-session-ai`; set its ID in `wrangler.jsonc`.
3. Run `npm run db:migrate` to create the schema.
4. Set `GROQ_API_KEY` with `wrangler secret put GROQ_API_KEY`. Enter it through
   the hidden CLI prompt; never put it in this repo, shell arguments, or the APK.
5. Deploy with `npm run deploy`. `/health` reports configuration readiness only.
6. Set both clients' callable URL to the deployed `/explainTrainingSession` URL,
   rebuild/deploy the website, and package a new Android APK.

The model defaults to `openai/gpt-oss-20b`. Its optional `GROQ_SESSION_MODEL`
variable changes the model and invalidates old cache fingerprints. Groq's free
limits are shared by the organization; token limits can be reached before the
published request-per-day cap.

## Storage and limits

D1 stores only validated explanation prose, fingerprints, hashed owner/session/
caller references, timestamps, and counters. Tokens, names, recordings, notes,
and raw measurements are never stored there. Every cached read is gated against
current Firebase access and re-derived session evidence.

A single atomic D1 batch claims a session lease and grants the caller up to
20 generation attempts per UTC day with a 15-second cooldown. Failed provider
attempts count. Concurrent attempts cannot independently claim the same session;
cached reads do not spend generation quota. Provider calls are limited to 45s.

Cache validity is at most 24 hours. An hourly scheduled purge removes expired
rows and usage counters older than 48 hours. Physical removal can lag expiration
by up to the hourly interval under normal operation. Spark provides no Firestore
deletion trigger for this service; deletion/revocation immediately blocks future
reads through the gate, while anonymous cache rows expire on this bounded
schedule. Existing Firebase account-deletion services are a separate deployment.

Workers Free has a 10ms CPU limit per invocation. Network waits do not count;
source parsing, evidence aggregation, hashing, and validation do. Keep the runtime
small, test cold/warm requests and maximum practical session sizes on Cloudflare,
and observe invocation CPU before promising it fits every session. A CPU or
upstream failure returns an unavailable explanation; measured feedback remains.

The Free endpoint accepts at most 100 saved reps per explanation. A bounded
metadata read rejects larger sessions before fetching measurement arrays. The
full Firestore response is streamed with a 128 KiB ceiling before JSON parsing;
the actual rep array is also checked, so a forged smaller repCount cannot bypass
the limits. Normal session saving and measured feedback keep their existing
limits. Oversized AI requests return a clear shorter-practice-set suggestion.

`npm test` exercises the protocol, Firestore authorization boundaries, output
validation, cache invalidation, D1's actual SQLite statements, leases, quotas,
deletion/revocation during inference, and scheduled expiry. It does not claim
physical-device or hosted CPU validation.
