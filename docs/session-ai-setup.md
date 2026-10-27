# Saved session AI explanations

Android session summaries and coach review, plus web shared sessions, use a
Cloudflare Worker for the Groq explanation service. Firebase Auth, Firestore,
and Hosting remain on Spark. The original Firebase Functions adapter remains
optional and requires Blaze; the Worker does not call it.

## Evidence and behavior

- Opening a session checks only for an existing explanation. A user explicitly
  chooses **Generate explanation** before a new Groq request is made.
- Clients send only a saved session ID and a generation flag. The Worker reads
  the source from the fixed `volleyball-lab` Firestore project. Client-supplied
  scores are rejected.
- A cue needs at least three measured reps. At most two strengths (score at least
  75) and two priorities (score below 70) are selected deterministically. Sparse
  cues are excluded. Reference ranges are app coaching references, not universal
  or clinically validated standards.
- Groq receives selected cue outcomes (average position below, within, or above
  the app reference) and approved tips. Exact numeric values stay in the score UI.
  It receives
  no account/session IDs, names, emails, recordings, raw landmarks, or coach notes.
- The fixed prompt and response validator restrict cue IDs, structure, lengths,
  numerical prose, and medical claims. These checks reduce unsupported output;
  they do not prove every generated sentence is correct.
- Coaches manually write and send their notes. AI output is not automatically
  sent to players as a coach note.

## Hosting and credentials

Source and deployment instructions are in [ai-worker](../ai-worker/README.md).
The deployed endpoint is
`https://volleyball-lab-session-ai.volleyball-lab-session-ai.workers.dev/explainTrainingSession`.
The public HTTPS endpoint is selected with `VITE_SESSION_AI_URL` for web and
`sessionAiUrl` (Gradle property) or `SESSION_AI_URL` (environment variable) for
Android. Both Firebase callable SDKs support a direct URL and automatically send
the Firebase ID token. A blank URL retains the original named Firebase callable.

Keep `GROQ_API_KEY` in a Cloudflare Worker secret. Do not put it in an APK,
Vite variable, committed configuration, shell argument, or browser source. Use
Wrangler's normal authorization flow; OS keyring storage is preferred on Windows.
Only Workers, D1, account/user identity, and Worker tail permissions are needed.
A Firebase service-account private key is not required.

The default model is `openai/gpt-oss-20b`, with strict JSON-schema output, a
45-second overall upstream timeout, one bounded retry for transient server
errors, and local output validation. Groq's free limits are organization-wide;
token limits can be reached before the published 1,000 requests/day cap. Check
the current [model page](https://console.groq.com/docs/model/openai/gpt-oss-20b),
[structured-output support](https://console.groq.com/docs/structured-outputs),
and [rate limits](https://console.groq.com/docs/rate-limits).

## Authorization and retention

Firestore validates the bearer token on source reads and on the virtual
`aiSessionAccess/{sessionId}` path. The latter stores no document: an authorized
GET returns not-found. Its rules require an existing session, neither account
being deleted, and either ownership or COACH role plus an Active roster connection
and explicit sharing. Lists and writes are denied. Historical session-read
behavior outside AI is preserved.

Every cached read is reauthorized. Source and access are checked again after
Groq responds. D1 stores validated prose, fingerprints, hashed identity
references, leases, and counters; it stores no ID tokens or raw sessions.
Cache validity is at most 24 hours, with hourly expired-row cleanup. Physical
removal may lag expiry by up to the hourly interval under normal operation.
Deletion immediately prevents future authorized cache reads through Firebase
rules; Spark provides no Firestore deletion trigger to purge D1 instantly.
Counters older than 48 hours are purged as well.

Atomic D1 operations enforce one in-flight generation per session, 20 attempts
per account per UTC day, and a 15-second cooldown. Failed attempts count; cached
reads do not. These limits are separate from local Android report allowances.
CORS allows the production web origins and local Vite. Android calls without an
Origin header still require valid Firebase authorization.

Workers Free has a 10 ms CPU allowance per request; network waiting does not count.
AI requests are bounded to short sessions (at most 100 reps and a bounded source
response) to keep parsing and validation small. Larger sessions retain normal
measured feedback. Benchmark hosted cold/warm requests before claiming a workload
fits this allowance. See [Cloudflare limits](https://developers.cloudflare.com/workers/platform/limits/).

## Verification

```powershell
cd ai-worker
npm ci
npm test
npm run check
npx wrangler deploy --dry-run
```

`functions/emulator-tests/session-ai-access.test.mjs` verifies the virtual gate.
`SessionExplanationDecoderTest` verifies Android decoding. The 32
Functions unit tests cover the shared policy and legacy adapter. Six new
authorization rule tests passed on 2026-10-27. Hosted inference/CPU verification
and physical-device validation are separate from these local checks.
