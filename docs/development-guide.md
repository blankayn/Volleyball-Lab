# Architecture and pose pipeline

The Android app uses a single module with separate UI, domain, data, and pose
processing packages. See the [repository overview](../README.md) for build steps
and the [documentation index](README.md) for deployment and validation records.

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

**Multiple people in view.** MediaPipe detects up to three poses. The player
starts in the centre for the framing gate; after that, a continuity tracker keeps
the same person when pose result order changes. A bystander does not silently
take over when the player briefly leaves the frame.

**Video review.** CameraX records a silent MP4 alongside pose analysis when the
device supports the preview, analysis and video use cases together. The result
screen plays it from app-private storage. If video binding fails, the camera
falls back to pose-only capture and still saves the scored session. Deleting a
session removes its private video.

**MPJPE evaluation.** The revised manuscript sets a research target below 50 mm
for each of the five skills. `tools/pose-pipeline/evaluate_mpjpe.py` compares the
app model's metric 3D joints with independently measured 3D ground truth and
reports overall and per-skill error plus annotation coverage. There is no
measured MPJPE claim yet; the evaluator needs calibrated reference data. Its
input contract and limitations are in `tools/pose-pipeline/README.md`.
