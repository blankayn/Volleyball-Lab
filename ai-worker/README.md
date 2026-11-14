# Session AI and private recordings on Cloudflare Workers

This service handles saved-session explanations, live angle coaching, and private R2 recordings. Firebase Auth and
Firestore remain on Spark. It accepts the Firebase callable JSON protocol at
`/explainTrainingSession`, so Android/web Firebase Functions SDKs can use a custom
URL while automatically sending their Firebase ID token.

## Adaptive starting-plan cards

`POST /personalizeTrainingSetup` accepts the Firebase callable `{ "data": ... }`
envelope with this exact versioned data shape:

```json
{
  "version": 1,
  "mode": "next_card",
  "answers": {
    "goalSkill": "Serving",
    "experience": "Developing",
    "note": "Madalas lumalabas ang serve ko.",
    "followUpQuestionId": null,
    "followUpOptionId": null,
    "weeklySessionGoal": null
  }
}
```

Goals are the five skill enum names or `NotSure`; experience uses `New`,
`Developing`, or `Experienced`. The optional note is represented by an empty
string and is limited to 160 characters. Every request is limited to 8 KiB.
Unknown fields, including account identifiers, scores, sessions, and recordings,
are rejected. English, Filipino, and Taglish notes are understood; generated
guidance uses short English text. No account identity is added to model input.

`next_card` requires null follow-up/schedule fields and returns
`{ result: { status: "question", source, focusSkill, questionId, acknowledgment } }`.
The acknowledgment is limited to 160 characters. The model can select only a
catalog question appropriate to the stated focus; the app supplies its fixed
question text and answer options. The bank in `src/training-setup.mjs` defines:

| Question ID | Accepted option IDs |
| --- | --- |
| `serving_focus` | `toss`, `contact`, `accuracy`, `not_sure` |
| `serving_consistency` | `toss`, `contact`, `accuracy`, `not_sure` |
| `receiving_focus` | `ready_position`, `platform`, `control`, `not_sure` |
| `receiving_control` | `ready_position`, `platform`, `control`, `not_sure` |
| `setting_focus` | `hand_position`, `contact`, `accuracy`, `not_sure` |
| `setting_control` | `hand_position`, `contact`, `accuracy`, `not_sure` |
| `spiking_focus` | `approach`, `timing`, `contact`, `not_sure` |
| `spiking_timing` | `approach`, `timing`, `contact`, `not_sure` |
| `blocking_focus` | `ready_position`, `timing`, `hand_position`, `not_sure` |
| `blocking_timing` | `ready_position`, `timing`, `hand_position`, `not_sure` |
| `general_focus` | the five skill enum names, `not_sure` |

Each skill has a broad introduction and a targeted consistency, control, or timing
variant. AI chooses between the allowed same-skill questions using the note and
stated experience; it cannot replace an explicit goal with another skill. Local
quick setup keeps the broad `*_focus` question.

`summary` requires one valid follow-up question/option pair and an integer
`weeklySessionGoal` from one to fourteen. It returns
`{ result: { status: "summary", source, focusSkill, summary, reason } }`, with a
240-character summary and 160-character reason. Structured answers determine the
focus; the provider cannot override an explicit goal or create a measured skill
rating. A player who remains unsure receives an editable Serving suggestion.
The app resolves a Free starter drill from its existing catalog, reviews the
suggestion with the player, and saves confirmed preferences separately.

The app makes at most one follow-up and one summary generation per setup attempt.
Both operations share ten provider attempts per authenticated user per UTC day
and two hundred across the organization. Attempts have a one-second caller
cooldown, count provider failures, and use the existing anonymous usage table.
Firestore verifies the Firebase token and account deletion fence before usage
or inference and is checked again before responding. Provider calls use the
existing Groq model with a 3.5-second timeout and no retries. Missing configuration,
quota exhaustion, invalid output, and provider failures return a catalog-based
result with `source: "local"`; valid AI results have `source: "groq"`.

The Worker does not store notes, generated setup prose, transcripts, names, or
tokens. It stores only hashed usage references/counters, removed by the existing
48-hour cleanup. The deployment has request observability disabled. These setup
suggestions are based on self-reported preferences, not camera measurements.

Recordings use authenticated `/recordings/{sessionId}/video.mp4`, `review.json`
and POST `complete` routes. Create private R2 bucket `volleyball-lab-recordings`
and bind it as `RECORDINGS` before deploying. Both clients send Firebase ID tokens
in bearer headers; the same virtual access gate below protects every request.
PUT is owner-only, and GET/HEAD additionally allow explicitly shared active coaches.
The video ceiling is 90 MiB, feedback 10 MiB. Stream uploads with Content-Length
and the exact MIME type; do not buffer videos inside the Worker. Files are
immutable once uploaded, and completion requires both objects. No expiry or public
bucket access is enabled. R2 overages can incur charges independently of the
Workers plan. See `../docs/account-and-recording-services.md` for retention,
legacy playback and account-deletion limitations.

## Required access gate

Deploy the `aiSessionAccess/{sessionId}` virtual allow-get rule before activation.
The rule checks both deletion fences and requires the owner, or an explicitly
shared COACH with an Active roster connection. No gate document is stored; an
authorized REST read returns `404 NOT_FOUND`. Missing rules fail closed with 403.
The Worker also requires a successful source session read using the same token.
Google verifies the ID token and existing rules; the Worker has no Firebase
administrator credential. Saved-session explanations never accept client-provided scores.
Live coaching separately accepts a bounded local measurement summary without
writing or verifying a saved session. See `../docs/live-coaching.md` for its
evidence policy, hands-free flow, quotas, and free-only voice activation.

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
and raw measurements are never stored in D1. Explicitly submitted videos and pose
feedback reside separately in private R2. Every cached explanation read is gated against
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

`npm test` exercises the protocol, Groq adapter, Firestore authorization boundaries,
output validation, cache invalidation, D1's actual SQLite statements, leases,
quotas, deletion/revocation during inference, and scheduled expiry. All 21 tests
passed on 2026-10-27. Thirteen hosted checks with synthetic sessions also passed,
including real Groq inference and a 100-rep session through the web Firebase SDK.
This does not claim physical-device or dedicated CPU benchmark validation.
