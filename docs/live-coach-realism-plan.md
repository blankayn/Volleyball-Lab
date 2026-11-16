# Live session coach: realistic coach assistance

**Implementation plan** · 16 November 2026 · branch `codex/capture-save-recovery`

## Outcome

Today the live coach is correct but sounds like a meter. Every rep is
`"Rep 3 of 5. Form target met."`, the opening is one glued sentence
(`"Maya, Focus on your forearm platform. …"`), an angle break is a count plus a
turn order, and the session ends on `"Session complete."`. The AI advice is
never heard, only shown on a card the athlete is standing metres away from.

After this plan the athlete should hear a person working a drill with them:

- **Greets and briefs**: name, today's focus, what happened last time, the plan.
- **Counts like a coach**: a number and a short reaction, not a graded sentence.
- **Notices things**: streaks, halfway, last one, form slipping late, a stubborn
  fault, the camera losing and finding them.
- **Says what it saw, then what to do**: "Your knees are staying a bit straight.
  Bend a little more."
- **Uses the break**: one takeaway from that view, spoken, then the turn.
- **Closes the session**: what went well, the one thing to carry forward.
- **Sounds different for different things**: counts brisk, corrections slower,
  pauses between sentences.

Every one of these stays tied to measured evidence. This plan changes what is
said, when, and how it sounds. It does not change what is measured.

## Guardrails (read before editing)

1. The working tree has about 80 uncommitted modified files. Run `git status`
   first. Build on the tree as it is. Do not revert, reformat, or stash.
2. No change to scoring, cue geometry, catalog ranges, `LivePostureCoach`
   thresholds, rep counting, stage gating, or the saved session contract.
3. Grounding rules that must survive, all pinned by `CoachDirectorTest` and
   `PersonalizedLiveCoachingTest`:
   - A correction needs three consecutive comparable reps (same cue, phase,
     side, view), two misses in one direction, latest still missing.
   - "In range / matched" praise needs two retries inside `cue.target` with
     score ≥ 0.92. Otherwise only "closer".
   - Unknown view, missing joints, non-finite scores, partial coverage
     (`isAssessable == false`) and `CueId.SpikeJumpHeight` never earn praise,
     a streak, or a verdict.
   - Good-rep wording must not say "better" or imply improvement.
   - View change, tracking gap, backgrounding and `beginStage()` never bridge
     evidence.
4. No microphone. `RECORD_AUDIO` is deliberately absent from the manifest.
5. Names, free-text goals and coach notes never go to the cloud. Only catalog
   ids and closed enums.
6. Phrase variety is deterministic rotation (index or ledger), never `Random`.
   Tests replay fixed sequences.
7. Phrasebook content rules stay: each instruction ≤ 110 chars, no digits, none
   of `ball|wall|toss|feed|rebound|always|perfect|video`.
8. Verification is JVM unit tests and Worker tests only. Do not adb-install or
   drive the owner's phone. Instrumented tests are updated to compile, not run.
9. One phase per commit set. A phase is done only when its **Done when** passes.
10. Line numbers below were read from the uncommitted tree on 16 November.
    Re-locate by symbol name if they have drifted.

Paths: `APP` = `app/src/main/java/com/volleyballlab/player`,
`TEST` = `app/src/test/java/com/volleyballlab/player`.

## Decisions already made

| Question | Decision |
|---|---|
| Keep `"Rep N of T."` spoken on every rep? | No. The card keeps `countText` unchanged. Speech uses a new short form (Phase 1). Tests pinning the old spoken string are updated on purpose. |
| Use Groq Orpheus voice inside a session? | No. Ten voice calls per user per day cannot cover a session, and switching voices mid-session is worse than one consistent voice. Phone TTS for everything in-session. |
| Speak the AI break advice? | Yes, as a bounded late-join (Phase 5): only if it arrives within 1 500 ms of the local debrief finishing. It never delays the countdown beyond that. |
| Athlete-to-coach input? | Touch only: a large "Say again" control. No voice input, no pose gestures in this plan. |
| Cross-session memory? | Local only, in the opening, citing stored facts. Nothing about past sessions is sent to the cloud. |
| Profile data to cloud? | Two closed values only: `experience` enum and `isAthleteFocus` boolean. `docs/live-coaching.md` currently says no profile data is sent, so update that sentence in Phase 6. If the owner objects, drop both fields; nothing else depends on them. |

---

## Phase 1: The coach's words (pure Kotlin, no flow changes)

Files: `APP/pose/analysis/CoachPhrasebook.kt`, `PersonalizedLiveCoaching.kt`,
`CoachDirector.kt`; tests `TEST/pose/analysis/CoachDirectorTest.kt`,
`PersonalizedLiveCoachingTest.kt`,
`TEST/feature/train/capture/GuidedCaptureIntegrationTest.kt`.

### 1.1 Split what is shown from what is spoken

In `LiveRepFeedback` keep `countText` and `detail` for the card exactly as they
are. Replace the computed `spokenText = "$countText. $detail"` with a field
built by a new `CoachPhrasebook.repTurn(...)`.

Spoken count rules (guided, target `T`, stage rep `n`):

| Rep | Spoken count |
|---|---|
| `n == 1` | "One." |
| `T` even and `n == T / 2` | "Halfway." |
| `n == T - 1` | "One to go." |
| otherwise | the number word ("Three.") |

Free practice: the number word only. Add a `numberWord(n)` helper for 1..30,
falling back to digits above that. The final rep of a stage is still spoken
inside the rotation or closing turn, not on the rep lane.

### 1.2 Reaction after the count

Add `CoachPhrasebook.repAck(verdict, focusId, index)` with rotating variants
(pick `index % size`, skip the previous text):

- `Good` (5 to 6 variants): "Clean.", "That's the shape.", "Good, same again.",
  "That one was in range.", "Yes. Hold that." No "better", no "improved".
- `Adjust` with no director turn (4): "Not quite.", "Close.", "That one was off.",
  and, when a focus is active, "Stay with your <focusName>." The first rep of a
  stage with a fault keeps naming the cue: "Your <focusName> needs work."
- `Partial` (2): keep the substring "Only part".
- `Unrated` (2): keep the substring "couldn't judge". Warmer:
  "Counted. I couldn't judge that one from here."
- Quiet retry (the rep after a director turn): count only.

When the director emits, the turn is `"<count> <director text>"`.

### 1.3 Corrections say what was seen

Change the signature to
`instructions(id, measured, side: Side? = null, experience: ExperienceLevel? = null)`.

- Rung 0 gains a direction-only observation, no numbers:
  "Your knees are staying a bit straight. Bend a little more." /
  "You're sitting a bit deep. Rise slightly."
- For side-resolved cues (`ServeContactArmExtension`, `ServeReachHeight`,
  `SpikeArmExtension`, `ServeTossArmLift`) insert "left" / "right" from `side`.
- Add rung 3 (struggle): "This one takes a few tries. Just think: <shortest action>."
  and rung 4 (feel cue, one per cue family).
- `ExperienceLevel.New` starts the ladder at the slow-rehearsal rung.
- Leave `action(id, measured)` wording untouched. `pose/review/CoachReel.kt`
  uses it.

In `CoachDirector`, pass `CueKey.side` through, and replace
`used: MutableSet<String>` with `lastSpokenRep: MutableMap<String, Int>` so a
sentence may return after ≥ 8 reps and ≥ 45 s. This removes the current
behaviour where a stubborn fault makes the coach go permanently silent after
three sentences.

### 1.4 Praise and missing-evidence wording

- `improvement(id, inRange)`: grow to 5 variants each. First in-range variant
  keeps "both attempts". "Closer" variants keep "closer" and never contain
  "matched" or "in range". Example in-range: "There it is. Your <name> was in
  range on both of those."
- `unavailable(id, angle)`: make it actionable using
  `AngleCuePolicy.affectedPoints(id)`: legs → "I'm losing your legs. A step back
  should fix it."; overhead arms → "Your hands are leaving the top of the
  frame." Keep "Reps are still recording" in the first variant.

### 1.5 Opening

Rebuild `LiveCoachingPlan.opening(skill)` to return `List<String>` segments
(one sentence each; Phase 3 speaks them with pauses). Add a
`openingText(skill)` that joins them for callers not yet migrated.

1. Greeting: "Hi Maya." (omitted with no name)
2. Focus: "Today's about your forearm platform."
3. Setup cue: existing `CoachPhrasebook.opening(skill)`, 2 to 3 variants.
4. Expectation: "I'll watch the first couple before I say anything." This is
   true: the director is silent for the first two reps of a stage.

History and assignment segments are added in Phase 4.

### Tests to update deliberately

- `PersonalizedLiveCoachingTest`: exact strings `"Rep 1 of 5. Your forearm
  platform needs work."`, `"Rep 1 of 5. Form target met."`, the opening
  `startsWith` / `endsWith` / `"I'll count each rep"` assertions. `countText`
  assertions stay.
- `CoachDirectorTest`: `repeatedCorrectionsTeachDifferentActionsWithoutNagging`
  (exactly 3 events in 30 reps becomes the new ladder length) and
  `phrasebookCoversBothDirections…` (`distinct().size == 3` becomes 5).
- `GuidedCaptureIntegrationTest` lines ~53, ~80: `startsWith "Rep N of 5."`.
  The assertion that the athlete's name is absent from rep speech stays true.

Add tests: count form table; no ack repeats back to back; rung 0 names the
right direction and side for every cue; a sentence can return after the
cooldown but not before.

**Done when** `:app:testDebugUnitTest --tests "*.pose.analysis.*" --tests
"*.feature.train.capture.*"` passes and no spoken rep string contains the word
"target met".

---

## Phase 2: Session-arc awareness in `CoachDirector`

Files: `CoachDirector.kt`, `CoachPhrasebook.kt`,
`APP/feature/train/capture/CaptureViewModel.kt` (call site only),
`GuidedCaptureProgress.kt` (read `LiveCaptureMetrics`, no change).

### 2.1 Give the director context

```kotlin
data class RepContext(
    val stageReps: Int, val stageTarget: Int?, val stageIndex: Int,
    val streak: Int, val bestStreak: Int, val validReps: Int,
)
fun onRep(assessment, timestampMs, cameraAngle, preferredCueIds, context: RepContext? = null)
```

`LiveCaptureMetrics` already tracks streak, bestStreak and validReps. Pass them
from the call at `CaptureViewModel.kt` ~557-581. `context == null` keeps
today's behaviour so existing tests are unchanged.

### 2.2 New turns, in priority order below corrections and improvements

All are `CoachingEventType.RepMilestone`, priority 0, share the
one-turn-then-quiet-rep rule, and never fire on a rep that carries a correction.

| Turn | Fires when | Example |
|---|---|---|
| Earned streak | `isAssessable && isGood`, streak ∈ {3, 5, 8}, no unresolved focus, ≥ 3 reps since last confirmation | "Three in a row. Your platform is holding." Name a cue only if it was inside target with score ≥ 0.92 on every streak rep; otherwise "Three in a row in range." |
| Regroup (form slipping) | mean score of last 3 assessable reps ≥ 15 points below the stage's first 3, and at least one earlier rep was good; or a cue confirmed in range has now missed twice in the same direction. Once per stage. | "Your knee bend was in range earlier and it's slipping. Take a breath, reset, then go." |
| Budget spent | the 4-in-10 correction budget is exhausted and a fault persists. Once per stage. | "Let that one settle. Just keep them coming." |
| Watch window | stage reps 1 and 2, no claim made | "Got it, keep them coming." / "I'm watching your <plan focus>." |

Keep the stage's assessable scores in an `ArrayDeque<Float>` cleared by
`beginStage()`. Do not use `MovementEvidence` for any wording; it forbids
quality inference.

### 2.3 Carry the open focus across a view change, in words only

Before `beginStage()` clears `focus`, remember `carryFocus: CueId?`. Old
observations are still discarded. If the same cue independently meets the
repeated-fault rule in the new view, prefix its first instruction with "Same
thing from this side:" and start at rung 1.

### 2.4 Session notes for the debrief and closing

Record a `StageNote(angle, reps, validReps, bestStreak, resolvedCues, openCue)`
at each stage end, surviving `beginStage()`, cleared by `reset()`. Expose
`stageNotes()` and `lastCorrection(): String?`. Phase 4 consumes them.

### 2.5 Don't burn a sentence the athlete never heard

Add `fun onSpeechDropped(sequence: Long)`. It removes the sentence from the
ledger, rewinds that focus's `nextInstruction` by one, and removes that rep
from `corrections`. Phase 3 calls it.

Tests: streak never fires on partial or unrated reps; regroup needs a measured
drop and fires once; carried focus cannot produce a correction without three
fresh reps in the new view; a dropped correction is offered again.

**Done when** all `CoachDirectorTest` cases pass, including the unchanged
evidence tests.

---

## Phase 3: How it sounds

Files: `APP/ui/coaching/VoiceEngine.kt`, `AndroidVoiceEngine.kt`,
`CoachingAudio.kt`, `SpeechPacer.kt`,
`APP/feature/train/capture/CaptureScreen.kt`; tests
`TEST/ui/coaching/SpeechPacerTest.kt`, `ReliableVoiceEngineTest.kt`; fakes in
`app/src/androidTest/.../CoachingAudioDeviceTest.kt` (compile only).

### 3.1 Speaking styles

```kotlin
enum class SpeechStyle { Count, Praise, Correction, Guidance, Urgent }
fun speak(text: String, utteranceId: String, flush: Boolean, style: SpeechStyle = SpeechStyle.Guidance)
```

`AndroidVoiceEngine` sets rate and pitch per utterance before `tts.speak`:

| Style | Rate | Pitch |
|---|---|---|
| Count | 1.08 | 1.00 |
| Praise | 1.02 | 1.06 |
| Correction | 0.92 | 0.97 |
| Guidance | 0.96 | 1.00 |
| Urgent | 1.05 | 1.00 |

These are starting values for the owner to tune by ear. Update the fakes in
`ReliableVoiceEngineTest.kt` and `CoachingAudioDeviceTest.kt`.

### 3.2 Multi-sentence turns with pauses

Add `playGuidance(segments: List<String>, style, instruction, onFinished)`. The
existing overload delegates to it. Insert
`playSilentUtterance(300 ms, QUEUE_ADD, …)` between segments. Only the last
chunk carries the completion id. The completion callback must still always
fire on mute, focus loss, error and timeout.

Scale the `ReliableVoiceEngine` watchdog from a fixed 15 000 ms to
`max(6_000, 90 * chars + 3_000)`. Derive the opening (12 s) and closing (10 s)
fallbacks in `CaptureViewModel` from the same estimate. Add a test: a long turn
does not retry. Keep `oldWatchdogCannotFinishReplacement` green.

Extract the segment queue and duration estimate into a JVM-testable class next
to `SpeechPacer`, with an injected clock and scheduler, and unit-test it.
`CoachingAudio` currently has no JVM coverage.

### 3.3 A correction outranks a count

In `CaptureScreen.kt` ~259-277 priority is derived from `repNumber != null`, so
a correction is treated exactly like a count and expires after 3 s.

- Add `SpeechPriority.Coaching` between `RepFeedback` and `Tracking`. Rep
  events whose type is `Correction` or `Improvement` use it, with a 6 000 ms
  expiry and the matching `SpeechStyle`.
- A plain count never replaces a pending `Coaching` item.
- Tracking and Rotation still outrank everything.
- Add `onDropped(key)` from `CoachingAudio` and wire it to
  `CoachDirector.onSpeechDropped`.
- Check `aNewRepReplacesAnOldUnspokenCount…` and the priority-order tests.
  Keep the 4 000 ms and 12 000 ms constants.

### 3.4 Softer edges

- `stopAfterCurrent()`: clear pending, let the active utterance finish. Use it
  when tracking is regained instead of `audio.stop()` (`CaptureScreen.kt`
  ~190-195).
- Hold audio focus for a 2 s linger after an utterance while recording, so
  back-to-back rep speech does not duck the athlete's music on every count.
- Voice selection in `AndroidVoiceEngine.kt` ~26-29: sort by `Locale.US` first,
  then quality, then name, so the coach's voice is stable across phones and
  voice-data updates. Still offline-only; an empty list still keeps the default.

**Done when** `:app:testDebugUnitTest --tests "*.ui.coaching.*"` passes and the
androidTest source set compiles (`:app:compileDebugAndroidTestKotlin`).

---

## Phase 4: The turns of a session

Files: `CaptureViewModel.kt`, `CaptureScreen.kt`, `LiveSessionControls.kt`,
`PersonalizedLiveCoaching.kt`, new `APP/pose/analysis/SessionWrapUp.kt`; tests
`GuidedCaptureIntegrationTest.kt`, `PersonalizedLiveCoachingTest.kt`.

### 4.1 A real opening brief

Extend `LiveCoachingPlan` with local-only fields, populated in `forAthlete` and
frozen at the countdown exactly like the plan is today (`CaptureViewModel.kt`
~335-342):

- `sessionsOnSkill: Int`, `lastSessionFocus: CueId?`, `lastValidReps`,
  `lastTotalReps`, read from `sessionRepository` filtered by skill and drill.
  Check the actual `SessionResult` fields before relying on these names.
- `dominantSide: Side?` from `profile.dominantHand`. Use
  `preferredSide ?: plan.dominantSide` at `CaptureViewModel.kt` ~659 so the
  side-view instruction stops falling back to a generic "Turn sideways".
- `assignmentTitle: String?`, `humanCoachName: String?` from `LiveCoachContext`.

Extra opening segments, each only when its fact exists:

- Returning: "Welcome back, Maya. Last time we worked on your knee bend, so
  let's see it early."
- Assigned: "Coach Reyes set this one: <assignment title>." Never read a
  free-text note aloud.
- Plan: "Front view first, five reps, then side."

Speak through the multi-segment `playGuidance`. `onOpeningFinished()` still
releases the gate.

### 4.2 Natural starts and recoveries

Publish non-rep events (`RepMilestone`, `repNumber = null`):

- After 3-2-1 in `beginRecording`: "Go when you're ready."
- After a rotation countdown: "Same again from this side."
- `CoachDirector.onTrackingRestored(ts)`: "Got you again, carry on.", only if a
  tracking prompt was spoken in the last 20 s. Publish at
  `CaptureViewModel.kt` ~549 after the 1 000 ms settle.
- Idle: no rep for 12 s while tracking is reliable → "Whenever you're ready."
  Once per idle spell, from the existing timer tick (~933-953).
- Setup: in `Framing`, speak the primary framing issue after it persists
  1.5 s, and "That's it, hold still." when the hold ring starts. The pacer's
  12 s repeat rule already prevents nagging.

Wrong-view text must still start with "Face the camera again" and the
missing-body text must still contain "whole body is visible".

### 4.3 The break is a debrief

In `requestRotation` (~650-700) build three segments:

1. The final rep's turn (unchanged position; `rotation.message` must still
   start with the final rep text).
2. One stage line from `stageMetrics` plus the local takeaway whenever
   `evidence.focus() != null`, regardless of the AI switch: "Front view done,
   four of five in range. Your knee bend kept drifting straight. Bend a little
   more." Use `CoachPhrasebook.action(cueId, measured)`, which is
   direction-correct. With no fault and a strong cue: "…and your platform held
   the whole way."
3. The turn instruction.

Replace the `reactiveCoach.shortCueText()` call at ~681. It is not
direction-aware and can point the wrong way.

### 4.4 A closing on every ordinary finish

New `SessionWrapUp.closing(plan, total: LiveCaptureMetrics, notes: List<StageNote>, endedEarly: Boolean): List<String>`:

- "That's fifteen reps, Maya."
- "Eleven were in range, with a best run of four."
- One win, only if a `StageNote` records a resolved cue: "Your platform came good."
- One carry-forward, the open cue: "Next time, keep working the knee bend."
- "Your review is ready."

Ended early: honest and short, no praise that was not earned.

Generalise `finishAfterRep()` into `finishWithClosing(segments)` and route
guided auto-finish, Finish, Skip Rear and confirmed End Early through it. The
recording limit and forced stops stay immediate. Keep
`onFinalRepSpeechFinished(sessionId)` session-scoped with a bounded fallback
from the Phase 3 estimate, and keep audio active through the closing
(`CaptureScreen.kt` ~186 currently stops it on `Analysing`). The save and
recovery flow in `CaptureCompletion.kt` must not change.

### 4.5 "Say again"

Add a large control in `LiveSessionControls.kt` that calls
`CoachingAudio.repeatLast()` (the last correction, else the last guidance
turn). Add a "coach says" line to `SessionRepFeedback` for non-rep coach lines,
which are currently hidden whenever a rep verdict is shown. Keep test tags
`capture-viewfinder`, `capture-camera-content`, `live-rep-feedback` and the
existing button labels used by `LiveSessionLayoutDeviceTest`.

### 4.6 Remove dead coaching code

`ReactiveCoachingEngine.onPosture/onRep/onTracking/onRotation` and its streak
and milestone lines are not called from capture. After 4.3 remove
`lastRepSummary` and the always-empty `drainDue()` poll from the view model.
Search the whole repo for other callers before deleting anything from the
engine itself; if any exist, leave the class and remove only the view model's
use.

Tests: opening includes history only when a prior session exists; closing
never claims a win without a resolved cue; closing plays on all four finish
paths and the session still saves when speech never completes; a late or stale
callback cannot finish a different session.

**Done when** `GuidedCaptureIntegrationTest`, `CaptureHoldGateTest` and
`GuidedCaptureProgressTest` pass.

---

## Phase 5: The AI coach at the break

Files: `ai-worker/src/live-coaching.mjs`, `ai-worker/test/live-coaching.test.mjs`,
`APP/data/ai/CloudLiveCoachingRepository.kt`,
`APP/pose/analysis/LiveCoachingEvidence.kt`, `CaptureViewModel.kt`.

### 5.1 Fix what is wrong today

- **Direction-blind tips.** The same tip is sent for "below" and "above", so a
  too-deep knee bend is told to bend more, and the fallback reads "Bend the
  knees was below the practice range." Add a `LIVE_DIRECTION` map in
  `live-coaching.mjs` for every cue:
  `{ noun, below: { saw, tip }, above: { saw, tip } }`, wording mirrored from
  `CoachPhrasebook`. Do **not** add fields to `SESSION_CUES`; it feeds
  `explanationHash` and client decoders.
- **Fallback grammar.** Three rotating frames chosen deterministically:
  "On that view your {noun} sat {saw}. {tip}".
- **Failure mode.** Move the quota reservations inside the try so a D1 error
  degrades to the local fallback, and map unknown errors to `UNAVAILABLE`
  503 instead of 400.

### 5.2 Give the model a coach's context, as closed enums only

Accept exactly two request shapes: the legacy four keys, or five keys with
`context`. Unknown keys are still rejected.

```
context: { v: 1, turn: "break" | "closing", breakIndex: 0..2,
           experience: "New" | "Developing" | "Experienced" | null,
           isAthleteFocus: boolean, corrected: CueId[] (≤ 4, unique, this skill) }
```

Confirm the real `ExperienceLevel` names before writing the enum.

The server derives the rest from the reps it already validates: `missShare`
(few / half / most), `trend` (closer / steady / further / unknown; needs ≥ 4
values and a change ≥ 0.2 × tolerance), `strengthNoun` (a cue with ≥ 3
measurements and zero misses), `lateDrop`. Raw measurements, names and free
text still never reach Groq. Add a test asserting that for the new fields.

### 5.3 Persona, praise, progress

- Rewrite the system prompt as a person: "You are a volleyball coach talking
  to your athlete at a short break. Second person, contractions, short spoken
  sentences, encouraging but honest. One thing to fix, said once." Keep every
  existing prohibition. Add three few-shot pairs (correction, correction with a
  strength, praise only), each passing the validator.
- New response `kind: "praise"` when there is no fault but a strength cue
  exists. Today a clean stage gets no AI turn at all.
- `validateLiveAdvice(raw, cueId, { allowProgress })`: permit "closer" wording
  only when the server-derived trend is closer. "always" and "every rep" stay
  banned. Never "fixed", "perfect", or anything about a previous session.
- When `corrected` includes the cue: "The athlete already heard this tip.
  Give a different feel-based way to do the same approved action."
- `turn: "closing"` produces a carry-forward and a grounded positive. The local
  closing from Phase 4 is always spoken; the AI version only upgrades the card.

### 5.4 Client

- `advise(evidence, voice, context: LiveCoachingTurnContext? = null)`. Add
  `kind` to `LiveCoachingAdvice` with a default at the **end** of the
  parameter list; `LiveSessionLayoutDeviceTest` constructs it positionally.
- Accept the server's cue when it belongs to the skill and has a matching local
  measurement, instead of requiring equality with the locally chosen focus.
  Add one shared JSON parity fixture used by both `LiveCoachingEvidenceTest`
  and `live-coaching.test.mjs`.
- Late-join speech: start the request when the stage reaches its target rep.
  If advice for the same `guidanceId` arrives before the local debrief ends
  plus 1 500 ms, speak it as one extra segment before the turn instruction.
  Otherwise card only, as today. The test `late AI advice never gates a turn or
  restores speech after rotation` must still pass; add a second test for the
  in-time case.

### 5.5 Quotas

A session is at most four text calls (three breaks and a closing). Lower the
per-user spacing from 15 000 to 8 000 ms and the organisation spacing from
2 200 to 250 ms; the 500-per-day organisation cap still bounds spend. Reserve
the organisation slot before the user slot so a shared-limit miss does not
burn the athlete's own quota. Voice quotas are unchanged.

**Done when** `cd ai-worker && npm test` passes, legacy four-key requests still
succeed, and the Android suites from Phases 1 and 4 still pass.

---

## Phase 6: Record and verify

1. Update `docs/live-coaching.md` with a dated section covering the spoken
   count form, new turn types, the closing, speaking styles, the cloud
   `context` contract, and the corrected "no profile data" sentence.
2. Update `docs/coaching-implementation-status.md` and `ai-worker/README.md`.
3. Add any new domain terms (debrief, closing, regroup, carry focus) to
   `GLOSSARY.md`.
4. Run the full suites and record the counts:

   ```
   .\gradlew.bat :app:testDebugUnitTest
   .\gradlew.bat :app:compileDebugAndroidTestKotlin
   cd ai-worker; npm test
   ```

   Gradle needs `JAVA_HOME` pointing at Android Studio's JBR. Worker tests need
   Node 22+.
5. Report what was **not** verified: voice rate and pitch by ear, speech
   timing at real rep tempo, audibility on court, and audio-focus behaviour
   with music playing. These need the owner's phone.

## What this plan leaves out

- Orpheus voice during a session, voice input, and pose-gesture commands.
- Any scoring, threshold, or accuracy claim. Coach-labelled validation in
  `docs/live-coaching.md` is still outstanding and unaffected.
- The Coach Reel and review screens.

## Handoff prompt

> Read `docs/live-coach-realism-plan.md` end to end, then `docs/live-coaching.md`.
> Implement Phases 1 to 6 in order, one commit set per phase, and stop a phase
> only when its "Done when" passes. Preserve the uncommitted working tree. Do
> not change scoring, cue geometry, catalog ranges, rep counting or the saved
> session contract. Keep every grounding rule in the Guardrails section; where
> a test pins a string this plan changes on purpose, update that assertion and
> say so in the commit message. Verify with JVM unit tests and Worker tests
> only; do not install on or drive a device. Finish with the list of what still
> needs checking on a real phone.
