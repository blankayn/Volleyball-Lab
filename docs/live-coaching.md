# Live coaching

Profile → Live coaching has independent Pose Guide and Detailed AI Coaching
switches, both initially off. Voice and haptics retain their existing defaults.
Preferences belong to the signed-in account on that phone.

Pose Guide draws a translucent gold reference limb with the athlete's observed
segment lengths. Measured bones are 2 dp with smaller joints; guide bones are
1.25 dp. The reference uses the current skill's catalog angle at the detected
movement phase. It supports elbow-angle cues across the five skills and knee
angle cues in a side view. Other cues continue to use their text guidance.
Uncertain tracking, hidden joints, unknown views, phase mismatches, ambiguous
bend direction, and depth-dominated projections suppress the guide. It does not
create a full-body target pose, alter measurements, or imply validated ideal form.
The existing reference ranges still require coach validation.

Detailed AI Coaching summarizes completed reps at a guided angle transition.
At least three measured repetitions and two misses on one cue are required;
only one cue is selected. Up to ten reps with four finite cue measurements each
are sent to `/coachLiveStage`. No video, images, raw landmarks, names, coach
notes, or account identifiers are sent to Groq. The Worker verifies the Firebase
token with an owner-only Firestore read, bounds and validates the local summary,
recomputes its focus using catalog ranges, and sends only the selected cue,
position bucket, and approved tip to the existing Groq text model.

These are client-computed live measurements, not independently verified saved
session evidence. The endpoint cannot save scores, change assignments, or share
recordings. AI prose is limited to 200 characters, checked against the selected
cue, and never used for scoring or reference geometry. Provider failure, invalid
prose, quota limits, or an eight-second client deadline preserve a local tip.
No repeated finding means ordinary measured guidance remains available.

At an angle break, pending short cues are cleared. The app waits for the complete
coaching turn and next-view instruction, confirms a stable view, then runs a
spoken and visual three-second countdown. Mute, unavailable speech, playback
errors, audio-focus loss, or a bounded playback watchdog release the speech gate.
Backgrounding suspends countdowns; resume and Retry assign a new guidance ID.
Old network results and speech callbacks cannot advance a different angle.
Skip and Finish cancel the old coaching work. The camera remains mounted.

Test coach voice is available in camera setup. Short reactive cues and countdowns
use installed English phone TTS. Optional Groq speech is fully downloaded before
playback; phrases are never played as separate streaming fragments. Both sources
share one audio lane and audio-focus request, respect mute/system volume, stop
when backgrounded, and delete temporary WAVs after playback. A real-device check
is still needed for voice quality, distance, speaker loudness, and court noise.

## Backend activation

Apply `ai-worker/migrations/0002_live_coaching.sql` to the existing D1 database
before deploying the Worker. Both Android routes are derived from the configured
`SESSION_AI_URL`; no new credential is placed in the APK.

Online speech is disabled unless `GROQ_VOICE_FREE_TIER_CONFIRMED=true` is explicitly
set on the Worker after confirming that the connected Groq organization is on
the Free plan. The owner confirmed Groq Free on 2026-11-08, so this deployment
sets the flag. A Groq API key alone never enables speech. No billing upgrade or
paid fallback is performed. Without confirmation, the feature uses phone TTS.
If the organization is later upgraded, remove the confirmation flag.

The Groq organization must also accept the Orpheus English model terms. A
`provider_400_model_terms_required` voice status means the provider is still
denying that key; phone TTS remains available. Accepting the terms must apply to
the organization that owns the stored key. Once Groq authorizes the model,
online speech can work without another APK or credential embedded in the app.

Speech uses `canopylabs/orpheus-v1-english`, voice `troy`, WAV, at most 200
characters. The Worker caps voice at ten attempts per user per UTC day and
ninety attempts for the app organization, with cooldowns. Groq's additional token
limits still apply and may be hit earlier. Text is capped at twenty attempts per
user and five hundred per app per day. All counters are atomic D1 upserts;
failed provider attempts count. No live advice or audio is retained in D1.
The scheduled purge removes counters older than forty-eight hours.

Provider references checked 2026-11-08:
[Orpheus speech API](https://console.groq.com/docs/text-to-speech/orpheus),
[organization rate limits](https://console.groq.com/docs/rate-limits), and
[structured output](https://console.groq.com/docs/structured-outputs).

## Verification record

On 2026-11-08, all 374 Android unit tests, 37 Worker tests, and five emulator UI
checks passed. The UI checks cover long coaching text, compact screens, action
visibility, the voice test, and keeping the camera mounted through state changes.
The live Worker returned authenticated Groq advice in roughly one to two seconds
with synthetic measurements, and rejected an anonymous request with HTTP 401.
The voice adapter and its fallback passed tests with a buffered WAV fixture;
the connected Groq account initially returned `model_terms_required` for actual
Orpheus speech. Voice quality and court-distance audibility require a real phone.
The final preview lint check passed with zero errors (214 warnings and eight
hints across the current workspace).
