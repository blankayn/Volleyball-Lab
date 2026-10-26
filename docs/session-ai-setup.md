# Saved session AI explanations

Android session summaries and coach review, plus the web shared-session panel,
can request a short explanation of saved cue measurements. Existing deterministic
feedback, video access, session saving, and coach notes work independently.

## Evidence and behavior

- A user explicitly chooses **Generate explanation**. Opening a session checks
  only for a cached result and never starts an inference.
- `explainTrainingSession` reads the saved Firestore session itself. Clients send
  only its ID and a generation flag; client-supplied scores are rejected.
- A cue needs at least three reps with finite measurements and valid scores.
  The server selects up to two strengths (score at least 75) and two priorities
  (score below 70). Sparse cues are excluded. Reference ranges are the app's
  coaching references, not universal or clinically validated standards.
- NVIDIA receives the skill, aggregate measurements, app reference ranges, and
  approved cue tips. It receives no names, account/session IDs, email addresses,
  raw landmarks, recordings, or coach notes.
- A fixed system prompt limits prose to that evidence and those approved tips.
  Strict output checks reject unknown/moved cues, invented numeric prose,
  invalid structure, and medical claims. These checks reduce unsupported output;
  they do not prove every generated sentence is correct.
- Coaches write and send their own notes. Generated text is never sent as a
  coach note automatically.

## Server configuration

Firebase project: `volleyball-lab`; Functions region: `asia-southeast1`.
The Firebase Blaze plan is required. Billing activation is an owner action.

Set the secret with Firebase CLI using a hidden interactive prompt:

```powershell
firebase functions:secrets:set NVIDIA_API_KEY --project volleyball-lab
```

Do not put the key in Android resources, the web bundle, Vite variables, source
control, or shell arguments. Rotate credentials exposed in chat before production.

`NVIDIA_SESSION_MODEL` defaults to
`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning`. The text-only integration uses the
NVIDIA hosted chat-completions endpoint, a fixed system prompt, a bounded output,
and a 45-second provider timeout. Model availability and hosted free API terms can
change; verify the [model catalog](https://build.nvidia.com/nvidia/nemotron-3-nano-omni-30b-a3b-reasoning)
and [API reference](https://docs.api.nvidia.com/nim/reference/nvidia-nemotron-3-nano-omni-30b-a3b-reasoning)
before production use. A successful synthetic request verified this model and
response decoding on 2026-10-26. No real athlete data was used in that probe.

Deploy only the relevant functions, preserving unrelated services:

```powershell
firebase deploy --project volleyball-lab --only functions:billing:explainTrainingSession,functions:billing:removeSessionExplanation
```

Deploy the updated account-deletion callable as well when activating this feature,
so deletion removes usage and explanation caches. Existing unrelated functions may
require their own configuration during source discovery; do not invent payment or
email credentials to bypass that requirement. Deploy web hosting after the callable
is active. Android clients require a new app build.

## Access, limits, and deletion

Authentication is required. An owner can access their session; a coach also needs
the COACH role, explicit `sharedWith` access, and an Active roster connection.
Account-deletion fences are checked, including after a provider request. Revoked
access prevents return of the newly generated explanation.

Results live in admin-only
`sessions/{sessionId}/aiExplanations/session-explanation-v1`. A fingerprint of the
evidence, prompt version, and model invalidates stale results. The server uses a
generation lease, a 15-second per-account cooldown, and 20 attempts per UTC day in
admin-only `aiUsage/{uid}`. Failed attempts count toward the limit. Cached reads
do not. These are separate from the existing Android local report allowance.
The callable has at most two instances and eight concurrent requests per instance;
these limits do not guarantee a spending cap. Web App Check is not configured, so
this callable currently relies on Firebase Auth, access checks, and these limits.

Deleting a session triggers cache removal. Account deletion removes its owned
session caches and usage counter. No additional client Firestore grants are needed.

## Validation

```powershell
cd functions
npm test
# With the Firestore emulator running:
node --test emulator-tests/account-cleanup.test.mjs
```

Android decoder tests: `SessionExplanationDecoderTest`. The isolated web fixture
can verify success/failure rendering without sending athlete data to NVIDIA.

Verification on 2026-10-27: 31 backend unit tests, two Firestore emulator
account-cleanup tests, and three Android decoder tests passed. Android compilation,
debug APK packaging, and the coach web production build passed. The isolated web
preview verified explanation rendering and draft preservation on success and
provider failure. This does not constitute physical-device or live endpoint
validation. Firebase Hosting was updated and its HTML and JavaScript returned
HTTP 200. Firebase still reported billing disabled, so secret provisioning and
Functions deployment remain pending Blaze. The web panel handles the unavailable
endpoint without blocking measured feedback or coach notes.
