# Volleyball Lab — Android player app

An AI-powered volleyball training and form-detection app. BSIT capstone,
University of Cebu, College of Computer Studies.

This repository is the **Android app**. It holds both halves of the product: the
player app, and — as of the coach-mode work — the coach experience too, running
on the phone behind the same login form rather than as the separate ReactJS +
Express web portal the document describes. See
[Coach mode](#coach-mode-one-app-two-shells) for what that changes and what it
does not.

The module implemented in depth here is **Training & Pose Capture Management**:
skill selection → drill selection → camera session → full-body pose capture →
pose-quality validation → AI feedback → session results. The surrounding app
shell exists so that flow can be demonstrated end to end.

---

## Build and run

Requires Android Studio 2026.1 or newer and an Android 8.0 (API 26) device.

1. Open the project in Android Studio and let it sync. The Gradle wrapper is
   configured for **Gradle 9.7.1** and will be fetched automatically.
2. **Install the pose model.** It is ~9 MB of binary weights and is not committed:

   ```bash
   curl -L -o "app/src/main/assets/pose_landmarker_full.task" https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task
   ```

   Without it the app still builds and runs; the capture screen reports the
   missing model instead of crashing. See
   [`app/src/main/assets/README.md`](app/src/main/assets/README.md).
3. Run on a **physical device**. The emulator has no usable camera feed and no
   GPU delegate. In debug builds, if the detector fails to start, the capture
   screen offers a scripted synthetic detector so the flow is still walkable —
   it is never substituted silently, and its scores mean nothing.

Verified locally with:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

121 unit tests pass; lint reports one cosmetic warning (see *Known issues*).

---

## Toolchain

Versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml);
no build file contains a version literal.

| | |
|---|---|
| AGP | 9.3.1 |
| Gradle | 9.7.1 |
| Kotlin | 2.4.10 |
| Compose BOM | 2026.08.00 (Compose 1.12.0, Material3 1.4.0) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| CameraX | 1.6.1 |
| MediaPipe Tasks Vision | 1.0.0 |

**AGP 9 note.** From AGP 9.0 the Android plugin has built-in Kotlin support.
Applying `org.jetbrains.kotlin.android` alongside it is now a hard error, not a
warning, so it is deliberately absent from both build files.

**No annotation processors.** No Hilt, no Room, therefore no KSP. Rationale is in
[`di/AppContainer.kt`](app/src/main/java/com/volleyballlab/player/di/AppContainer.kt)
— briefly: the graph is a handful of objects, and on a student laptop an extra processor
costs time on every edit. Everything is constructor-injected against interfaces,
so moving to Hilt later is mechanical.

---

## Architecture

Single `:app` module, layered by responsibility:

```
com.volleyballlab.player
├── ui/theme        Colour, type, shape and motion tokens
├── ui/component    Reusable animated primitives (ScoreArc, AnimatedCounter, …)
├── ui/icon         Hand-drawn skill pictograms, one pose per skill
├── domain/model    Skill, Drill, FormCue, TrainingSession, AthleteProfile,
│                   UserRole, UserPlan, CoachProfile, TeamRoster, SessionReview
├── domain/repository  Interfaces only
├── data            Current implementations (static catalogue, DataStore-backed
│                   sessions, profile, role, plan and coach data)
├── pose            The engine: detector, quality gate, analysis, scoring
├── feature         One package per screen, each with its ViewModel
│   └── coachmode   The coach shell: dashboard, roster, player, review, assign
├── navigation      Type-safe routes and the two NavHosts
└── di              AppContainer
```

### The pose pipeline

```
CameraX frame → Bitmap (upright) → PoseDetector → PoseFrame
                                                     │
                            ┌────────────────────────┴───────────────────┐
                            │ live                              post-hoc │
                     PoseQualityValidator                   RepSegmenter
                     LiveRepCounter                         SkillAnalyzer
                     PoseOverlay                            SessionScorer
```

Two details worth knowing before changing anything here:

**Two coordinate spaces, not interchangeable.** MediaPipe returns image-space
landmarks (normalised, perspective-projected) and world-space landmarks (metres,
hip-centred). Angles must come from world space or the same elbow bend measures
differently depending on how far the athlete stands from the lens. Drawing must
come from image space. Both live on `PoseFrame`, named for their purpose.

**Jump height cannot come from world space.** World landmarks are hip-centred by
definition, so the hips sit at the origin in every frame, standing or airborne.
`Angles.hipRiseMetres` instead measures hip displacement in image space and
converts it to metres using the athlete's own torso length as a scale reference —
recalibrated every frame, so it survives the athlete drifting nearer the camera.

**Unmeasurable is not zero.** A cue that could not be read — an occluded joint, a
side-on camera angle — scores `null`, and the remaining cue weights are
renormalised around it. Scoring it zero would blame the athlete for the camera
placement.

---

## Coach mode: one app, two shells

The capstone document draws the coach side as a desktop ReactJS portal
(Figures 23–29). This build runs it on the phone, in the same binary, behind the
**same login screen**. Nothing about the player app changed to make room for it.

### How an account gets into it

`VolleyballLabApp` reads the account's role once and builds one of two shells.
They share the splash and the two auth screens and nothing after them — different
tabs, different graphs, different back stacks. A role change rebuilds the graph
rather than navigating inside it, so a coach who switches to the player app
cannot press back into a review queue that is no longer theirs.

**Coaches register on the web portal, not here.** There is no coach option
anywhere on the mobile sign-up form and there should not be one — this app only
ever *receives* an account that already exists. The login form likewise asks
nothing about roles: `User_Role` is a property of the account (Table 7), so at
login the app should simply read what the portal wrote.

There is no backend to read it from yet, so two things stand in:

- **An address convention** — `coach@…`, or the plus-tagged `you+coach@gmail.com`.
  Exact, not a prefix match: `coaching@` and `headcoach@` are ordinary addresses
  and do not qualify. See `CoachAccounts`.
- **A switch in Profile**, and one back the other way in the coach's Account tab.

The address rule only ever *promotes* — it can grant the coach role, never take it
away — because a rule that could demote would silently undo that switch on the next
launch. The debug test-account buttons are the one exception: they name the shell
they open on their face, so they write the role in both directions.

### Getting into both sides for a demo

Debug builds carry a **Test accounts** block under the Google button on the login
screen, offering one tap into each half of the app:

| | address | password |
|---|---|---|
| athlete | `player@volleyballlab.test` | `VolleyLab2026` |
| coach | `coach@volleyballlab.test` | `VolleyLab2026` |

Both are **real** accounts — real uids, real sessions, the ordinary
`AuthRepository` path — because everything on both sides is scoped by uid, and a
mocked session would demo a code path that ships to nobody. The credentials live
in `DemoAccounts` and can safely: `.test` is reserved by RFC 2606, so neither address can
resolve to a real inbox, and the whole block is compiled out of release builds.
Delete an account from the Firebase console to reset it; the next tap makes a
fresh one.

**Why there is an athlete fixture too.** Anyone can sign up as a player, so it is
not there for access. Coach mode is a *relationship*, and a relationship cannot be
shown from one account: demonstrating it means being the player who asks and then
the coach who answers, back and forth. So the same one-tap switch appears inside
both shells — the athlete's Profile tab offers Demo Coach, the coach's Account tab
offers Demo Player — and neither route goes through the login form.

`DemoSignIn` is the single seam all three controls share. Two things about it are
load-bearing and read as tidy-uppable:

- It runs on its **own process-lifetime scope**, not the calling ViewModel's.
  Signing in *is* the teardown — the role flow is a function of the signed-in uid,
  so the moment sign-in lands, the calling screen can be destroyed and its scope
  cancelled mid-sequence.
- It writes the **role last**, after seeding names and publishing the coach's
  directory listing, because the role write is what swaps the shell.

**Demo in this order:** open Demo Coach *first* — that is what publishes the
listing — then switch to Demo Player, unlock Pro, choose the coach and send the
request. Switch back to approve it. Tapping Demo Player first shows an empty
directory, which is correct and looks like a bug; the empty state says so in debug
builds.

**The limitation this has, stated plainly:** the role lives on the device, per
uid. An account signing in on a *fresh* install lands in the player shell,
because nothing on that phone remembers otherwise, and the Profile switch is how
it gets corrected. Conflicts 4 and 6 below are what has to be settled before the
role can come from the account itself — at which point both stand-ins and the
test-account buttons all go away together.

### Screens, against the figures

| Screen | Document |
|---|---|
| `CoachDashboardScreen` | Fig. 25 — review queue and team figures |
| `CoachRosterScreen` | Fig. 26 — roster, plus Fig. 27's join queue |
| `CoachPlayerScreen` | Storyboard — 30-day trend, consistency, skill breakdown, history |
| `CoachReviewScreen` | Fig. 25's detail panel — scorecard, analysis, comment box |
| `CoachAssignListScreen` / `CoachAssignEditScreen` | Fig. 28 |
| `CoachAccountScreen` | Fig. 29 |
| `CoachLinkSection` (player side) | Figs. 17, 20, 21 and 22 |

Figure 25's two-pane layout becomes a list that opens a detail screen — nothing is
dropped, but a coach taps once more than a coach at a desk would. Everything else
in the figure set has a counterpart.

### How a coach and a player get linked

**The player chooses, and the coach answers.** This is the direction the updated
document specifies (Fig. 6.2) and it is worth stating plainly, because earlier
builds tried both alternatives:

1. A **Pro** athlete opens their Coach tab and browses a directory of coaches —
   name, specialisation, and how many athletes are already on the team (Fig. 20).
2. They open one profile, read who they are about to ask and the rules of the
   arrangement, and send a connection request (Fig. 21). Nothing is written until
   they send it, so backing out is free.
3. The coach approves or declines from the join queue on their Players tab
   (Fig. 27). Only the coach can move a request past `Pending`.

**There are no invite codes.** An earlier build had the coach publish a
six-character code for the player to type in. That was dropped and the type
deleted: a code makes the player prove they already know which coach they want,
which is backwards for a feature sold as *find a coach*. A briefly-tried
coach-adds-directly version was dropped too, for the consent reason below.

**Roster size is the only number on a listing the coach did not write about
themselves**, which is why it is the one worth showing. A listing deliberately
carries no contact address, no licence number and nothing about the coach's *other*
players — a directory that leaked squad names would hand out other athletes'
details to anyone who opened the tab.

**Coach connection is what Pro buys.** `UserPlan` is per-uid and the gate is
enforced in the ViewModel, not only in the UI, so a screen that forgets to check
cannot silently create a membership. One deliberate exception: an athlete whose
plan drops back to Free **keeps a link they already have**. Locking an existing
relationship behind a paywall would be taking something away rather than gating
something new. See conflict 7 for what the upgrade button actually does.

**Consent runs the right way now.** Being on a roster is something the athlete
asked for; the coach could only accept an offer that was made. The athlete's
ongoing control is that their Coach tab names the team with **Leave team** right
there, and that their sessions reach the review queue only when they submit one.
`SessionDirectory` — the single narrow seam through which one account's training
reaches another's screen — documents that its only legitimate caller is the coach
shell, and only for a uid that came out of the roster flow.

**This is device-local.** A player can only find a coach whose account has been
used on the same handset. The linkage itself is genuine — real uids, real sessions
— it is simply not distributed yet, for the reason conflict 4 gives. When there is
a backend, `CoachRepository` is the interface it implements and nothing above it
changes.

### What the "AI-Assisted Analysis" panel actually is

`SessionReview` pools the per-rep cue scores already stored with a session, sorts
them, and takes the top and bottom few. There is no model. The screen says so, in
those terms, under the panel — along with the fact that every threshold behind it
is a first-pass coaching value rather than a measured one, because Experiment 2
has not run. The same goes for the safety panel: a flag there means a joint angle
sat outside a band we guessed at and the clip is worth watching. It is not an
injury assessment and the copy must not drift toward implying that it is.

The player-detail screen's "AI-recommended corrective drill" is likewise shown as
what it is — the lowest-scoring cue — computed by the same rule the athlete's own
Coach tab uses, so the two screens cannot tell the same person different things.

### What the notification switches do

Persist a preference, and nothing else. Delivery needs the Notification entity in
Table 18 and a scheduler, neither of which exists here. A line under the switches
says so.

---

## Design system

Three colours are fixed anchors; everything else is a tonal ramp built around
them.

| Role | Hex |
|---|---|
| Primary — burnt orange | `#CC5500` |
| Page background (`background` / `surface`) | `#F0F8FF` Alice Blue |
| Ink (`onSurface`) | `#141C33` |

Because the page is tinted rather than white, the container ramp steps *down*
in lightness from it — a card at `surfaceContainer` is a shade deeper than the
page it rests on, and only `surfaceContainerLowest` goes the other way to pure
white. Cards lighter than a tinted page read as holes cut in it.

Full derivation is documented in
[`ui/theme/Color.kt`](app/src/main/java/com/volleyballlab/player/ui/theme/Color.kt).
Material You dynamic colour is deliberately **not** enabled: the rust primary
carries meaning (it marks the record control and the active training state) and
letting the wallpaper repaint it would break both the brand and the affordance.

**Typeface.** The reference specifies **Hanken Grotesk**. It is not bundled yet,
so the app currently resolves to the platform sans. Swapping it in is a one-line
change at `VbFontFamily` in
[`ui/theme/Type.kt`](app/src/main/java/com/volleyballlab/player/ui/theme/Type.kt),
which documents the exact steps.

### The coach shell's dialect

The coach half uses the same palette and the same tokens, laid out differently,
in
[`feature/coachmode/CoachModeUi.kt`](app/src/main/java/com/volleyballlab/player/feature/coachmode/CoachModeUi.kt).
Two deliberate departures, both about what the two halves of the app are *for*:

- **Cards sit at `surfaceContainerLowest` (white) with a 2dp shadow**, where the
  athlete's screens use `surfaceContainerHigh` and read as recessed. A training
  screen is one thing at a time; a coach page is a long scroll of unrelated
  blocks — a queue, a stat strip, a roster — and lifting each off the tinted page
  is what keeps that legible.
- **Four categorical accent tones** (`CoachAccent`), each a pale tint behind a
  saturated glyph, assigned by *what a thing is* and then never moved: rust for
  people, indigo for review and queues, green for sessions, violet for written
  material. This exists precisely so the rust primary can stay an affordance —
  the thing to tap, the thing that needs attention — instead of being spent on
  decorative fills.

Everything else follows from those: 24dp card radii, icon chips leading every
section, ExtraBold figures over small grey labels, dashed wells for empty lists,
and exactly **one** dark navy `CoachFeatureCard` per screen carrying the single
thing the coach is being asked to act on. One per screen is the whole mechanism —
on a page of white cards a second navy card costs the first all of its emphasis.

### Motion

Every animation pulls its duration and easing from `VbMotion`. Nothing calls
`tween(300)` with a literal.

All motion helpers collapse to zero duration when the user has disabled system
animations — `LocalReduceMotion` watches `ANIMATOR_DURATION_SCALE` with a
`ContentObserver`, so toggling the setting and returning to the app takes effect
immediately rather than at next process death.

The splash implements the state machine specified in
[`docs/rive/splash-contract.md`](docs/rive/splash-contract.md) — the same Intro /
Idle / Outro timings and the same `isReady` boolean semantics, including the
`Intro → Outro` transition that contract calls out as the one people forget.
Dropping in a real `splash.riv` later replaces one composable body and nothing
else. Section 7 of that contract left the splash background colour blank; it is
now decided: **`#F0F8FF`**, the same Alice Blue the app's pages use, and it must
stay identical in `Color.kt` (`VbSplashBackground`) and `res/values/colors.xml`
or there is a visible flash at the handoff.

---

## Capstone document conflicts

Three were resolved outright to make this build possible, and a fourth is now
modelled on the device. Five remain open and still need the team and adviser.

The numbering below is stable — other sections cross-reference it — so a conflict
that has since moved keeps its number and says so in place.

**Resolved**

1. **Real-time vs. post-hoc analysis** (Objective 3 vs. Fig. 14). Implemented as
   *both, at two fidelities*: a cheap live overlay, rep count and framing check
   during capture, then a full scoring pass over the buffered frames when
   recording stops. The results screen always reports the post-hoc numbers. The
   two rep counts can differ by one; that is expected, and documented in
   `LiveRepCounter`.
2. **Views vs. Compose** (Table 20 vs. Table 21). **Jetpack Compose**, matching
   the Table 21 narrative.
3. **Bottom navigation** (Fig. 11 vs. storyboard). **Fig. 11** — Home / Train /
   Progress / Coach / Profile. The storyboard version splits skill selection and
   capture across two tabs, breaking one task into two entry points and leaving
   the results screen homeless.

**Still open — these block real work**

4. **Firestore vs. the relational data dictionary.** The network model specifies
   Cloud Firestore; Tables 7–19 are MySQL with `INT(11)` keys. Until this is
   settled, everything persists as serialised blobs in Preferences DataStore —
   sessions, profile, role, and the whole coach side. There are no tables, no
   entities, no migrations and no annotation processor, deliberately: nothing in
   `data/` says how anything should be *modelled*, only that it should survive the
   process dying. Committing to a Room schema or a Firestore collection shape
   would pick a winner on the team's behalf. This is also why coach mode is
   device-local rather than networked.
5. **MoveNet** appears in the tech-stack prose but in no table and no other
   section. The app uses MediaPipe/BlazePose.
6. **Coach accounts have no home** in the ERD, and the portal's registration
   fields (Fig. 24) exist in no table. Coach mode needed somewhere to put them
   anyway, so `CoachProfile` combines Table 14's columns with the fields Figure 24
   actually collects, and `UserRole` stands in for Table 7's `User_Role` on the
   device. All three are the first things to move once this is settled.
7. **Free/Pro tier — now modelled, but only on the device.** Gated features exist
   in the UI mockups and in the Business Model Canvas; no plan or quota entity
   exists in the ERD. `UserPlan` stands in, per uid, and `ProFeature` splits the
   five claimed benefits into the two this build genuinely gates (coach
   connection, session comments) and the three it does not (detailed corrections,
   full drill library, advanced biomechanics). Listing all five as equals would
   be selling three things that are not there.

   **The upgrade is an explicit mockup, not a fake checkout.** No card fields, no
   price, no "payment successful" — a switch, and a plain statement placed
   *between* the feature list and the button so it cannot be skipped: no payment
   is taken and billing is not built. There is a *Switch back to Free* so the
   locked state can be demonstrated again without clearing app data.

   Two things to say in the write-up: the local flag is a *record* of an upgrade,
   not entitlement — anyone who can write to the app's data directory can set it,
   which is fine for a prototype whose upgrade button is itself a mockup and stops
   being fine the day money moves. And `PlanRepository` is what Play Billing would
   implement; the only change above it is that `upgradeToPro` becomes slow and can
   fail.
8. **Coach Mode drifts** between "AI personalisation" (Objective 4) and "a human
   coach reviewing remotely" (everywhere else). Coach mode now builds the second
   reading in full — a real person, a real roster, real notes written back — and
   the first stays exactly where it was: the athlete's Coach tab still pools their
   own cue scores across sessions and still says on screen that it was worked out
   on the device. The two sit on that one tab, in that order, without pretending
   to be each other. The drift in the document is still unresolved; what has
   changed is that neither reading is now blocked on it.
9. **Experiment 2 is still pending.** All twenty cue thresholds in
   `FormCueCatalog` are first-pass values from general coaching guidance, not
   measured data. They are gathered in one file specifically so the ten coach
   interviews can replace them as a contained edit. **Do not present the current
   numbers as validated.**

---

## What is real and what is not

**Real** — the design system; the splash; navigation; the whole pose pipeline
(detector, quality gate, segmentation, five skill analyzers, scoring); the
capture screen including permissions, framing gate, hands-free start, countdown,
live overlay and rep counting; the results screen; and all five tabs.

Progress, Coach and Profile were placeholders until the tabs were built out.
They are now real screens, and every number on them is **derived from sessions
the user actually recorded**. In the player app there is no sample data at all:

- **Progress** aggregates the stored sessions: totals, a form-score trend over
  the last ten, a per-skill breakdown and the full history.
- **Coach** has two halves. At the top, the link to a real coach: the directory
  they pick from, the request they send, whose team they end up on, sessions they
  choose to submit, the notes that come back, the drills they were set, and the
  way out. Below it, the on-device advice —
  every rep of every cue pooled across sessions, the weakest surfaced with the
  coaching tip already written for it in `FormCueCatalog`, and a note when too
  many cues came back unmeasurable to trust it. The screen keeps the two apart
  and does not let the second claim to be the first — see conflict 8 below.
- **Profile** holds the athlete's name, position and dominant hand in DataStore,
  alongside read-only training totals derived from sessions.

The coach shell is real on the same terms: every figure on it comes from sessions
its rostered players actually recorded on this device.

With nothing recorded, each screen says so and offers the way to fix it. That is
deliberate: during a defence, mock UI is indistinguishable from working UI, so a
chart with no data behind it is worse than an empty state.

**The one exception, and the condition it survives on.** A coach account with an
empty roster gets two stand-in athletes (`SampleTeam`) — Maya Reyes, improving at
spiking, and Andrei Cruz, whose block knee-load trips the safety watch. Coach mode
is device-local, so an examiner opening it on a fresh install would otherwise see
a roster of nobody, a queue of nothing and four dashes where the team figures go,
which demonstrates nothing about a screen whose whole job is reading a squad.

This is allowed because it is **not indistinguishable from real data**, which is
what the rule above is actually against:

- every row they appear on carries a **Sample** chip, and the dashboard says in
  plain words that they are not real;
- the whole set **vanishes the instant a real player joins** — including while the
  request is still pending;
- their uids are prefixed `sample-`, so any screen holding a bare uid can tell;
- and **nothing about them is hardcoded**. Those sessions carry real per-rep cue
  scores, and `PlayerSnapshot`, `TeamSummary` and `SessionReview` derive the form
  scores, trend, consistency and safety flags from them by exactly the arithmetic a
  real athlete goes through. There is no written-down "78" anywhere; the 78 is
  computed. That is the part a panel is actually asking about.

Labelled scaffolding that removes itself is a different thing from UI that lies
about having data. If that distinction ever stops holding, delete it.

**Stand-in** — the drill catalogue is compiled into the app
(`StaticDrillRepository`) pending conflict 4. Coach–player linking works only
between accounts that have both been used on this handset, for the same reason.
The Pro upgrade flips a local flag and charges nobody (conflict 7). Notification
switches record a preference and send nothing.

---

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

121 tests covering the parts where being wrong is expensive and invisible:

- `AnglesTest` — joint geometry, including that degenerate input returns `null`
  rather than zero, and that the image-space jump calibration is correct.
- `CaptureHoldGateTest` — the hands-free start: that holding still begins a
  session with nobody near the phone, that walking out of shot empties the gate
  instead of recording an empty gym, and that a cancelled countdown stays
  cancelled.
- `FormCueTest` — cue scoring, the graded tolerance band, and catalogue
  invariants (weights sum to 1.0 per skill, no duplicate or orphaned cue ids).
- `PoseQualityValidatorTest` — every framing rejection path and the stability
  window.
- `RepSegmenterTest` — drives the whole pipeline with `SyntheticPoseDetector` and
  asserts that a known number of movement cycles produces exactly that many reps.
- `TabDerivationsTest` — what Progress, Coach and Profile claim about training as
  a whole: that the trend runs forwards in time, that unscored sessions are
  excluded from averages rather than counted as zero, that advice is withheld
  until a cue has enough measured reps, and that nothing mediocre gets called a
  strength.
- `CoachAccountsTest` — the rule that decides which accounts open the coach app,
  including every near miss (`coaching@`, `headcoach@`, `kirt+coaching@`) that
  must *not* qualify, and both halves of the fixture pair: that the coach address
  satisfies the rule and that the **athlete address does not**. Either failing is
  silent and looks like the whole feature being broken — the first sends the demo
  coach into the player shell, the second promotes the athlete fixture and leaves
  nobody to demonstrate the other end of the relationship with.
- `CoachDerivationsTest` — what the coach screens claim about an athlete: that a
  trend needs enough scored sessions on both sides before it is printed, that one
  session is not "perfectly consistent", that a cue measured once is not a
  correction, that an unmeasurable session reads as a camera problem rather than
  a safety flag, and that the team average weights players equally instead of
  letting whoever films most decide the squad's number.

`SyntheticPoseDetector` is what makes `RepSegmenterTest` possible: it emits a
scripted articulated figure, so "did it count the reps" is an assertion rather
than someone watching a badge on a phone and deciding it looked about right.

---

## Known issues and limitations

- **Debug APK is ~74 MB.** MediaPipe ships native libraries for four ABIs. A
  release App Bundle splits per-ABI and is far smaller; if install time during
  development becomes painful, add an `abiFilters` block for debug builds.
- **Portrait only.** The framing gate assumes a standing athlete filling 45–95%
  of frame height, which is a portrait composition. Recent Android versions
  ignore the orientation lock on large screens, so on a tablet or unfolded
  foldable the capture screen will be poorly composed. Supporting landscape means
  redesigning the framing rules, not deleting a manifest line.
- **One lint warning**, `ObsoleteSdkInt` on `res/mipmap-anydpi-v26`. Lint is
  correct that the `-v26` qualifier is redundant at minSdk 26, but removing it
  makes aapt2 fail to find the launcher icon. The warning is left in place rather
  than suppressed globally.
- Rep segmentation for **Receiving** assumes the athlete returns to a taller
  stance between reps. A drill performed entirely in a low crouch will
  under-count.
- **Block penetration** is only measurable from a side-on camera and reports
  itself as unmeasurable from a front view, rather than returning a confident
  zero.
- **Coach mode is device-local.** A coach only appears in a player's directory if
  that coach's account has been used on the same handset. A coach signing in on a
  fresh install also lands in the player shell until they use the switch in
  Profile, because the role has nowhere off-device to live yet. Both follow from
  conflicts 4 and 6.
- **Being answered is silent.** The athlete finds out they were approved — or
  declined — by opening their Coach tab, not by being told. A decline is
  especially quiet: the pending card simply goes and the directory comes back.
  Telling them needs the Notification entity in Table 18, which nothing
  implements.
- **The Pro flag is local and unenforced.** It records that someone tapped
  upgrade; it is not entitlement, and nothing server-side agrees with it. Fine for
  a prototype whose upgrade button is itself a mockup — see conflict 7 — and the
  first thing to move when billing is real.
- **A directory listing is whatever the coach typed.** Name, team, organisation,
  specialisation and years of experience are all self-reported and nothing
  verifies them, including the certification licence number the registration
  figure collects. Roster size is the one figure on a listing the app computes.
