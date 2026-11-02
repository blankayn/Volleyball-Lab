# ML training progress — 30 October 2026

For the subsequent temporal comparison, uncertainty gates and Android coaching
implementation, see [AI Coach status — 31 October](ai-coach-system-status.md).

This pass collected public creator tutorials, reviewed explicit demonstrations,
extracted app-aligned pose features, and trained small development candidates.
It does not establish full-skill coaching accuracy or production readiness.

## Current corpus

| Skill | Eligible correct | Eligible faulty | Source-video groups | Status |
|---|---:|---:|---:|---|
| Serving | 2 | 2 | 2 | Unvalidated development fit |
| Receiving | 6 | 4 | 2 | Unvalidated development fit |
| Setting | 3 | 2 | 2 | Unvalidated development fit |
| Spiking | 2 | 2 | 3 | Unvalidated development fit; faults come from one source |
| Blocking | 2 | 0 | 2 | Not trained: no eligible faulty examples |

These are demonstration segments, not independently verified athletes or
necessarily independent repetitions. All cuts and camera replays from one video
retain the same source group. There is **no validated accuracy figure** for
these fits. Their small size permits memorization. AI-reviewed creator labels
and existing badge labels remain provisional.

The source investigation and rejected examples are recorded in
[`ml-dataset-sources.md`](ml-dataset-sources.md) and
[`ml-spike-source-followup.md`](ml-spike-source-followup.md).

## Quality and actor checks

The alternate quality profile is explicitly named
`features-v2-required13-visibility0.5-image-bounds`. It retains the original
all-33-landmark coverage values and separately measures the 13 points used by
the 24 feature columns. Every required point must have visibility at least 0.5,
finite geometry and coordinates within the image; the skeleton validity check
must pass. At least half of a segment's frames must satisfy that profile.

The profile does not verify actor identity. Visual landmark overlays rejected a
Wicked Volleyball spike segment that followed the stationary trainer instead of
the spiker, and an Olympic segment that followed the foreground setter. Neither
is a valid training example, regardless of its visibility score.

Blocking remains short of suitable faults. The apparently usable Coach Artie
106.3–111.9s example contains an arms-down ready stance and explanation, with
zero completed blocks. Other explicit faulty jumps failed visibility or image
bounds, or were cropped/crowded enough that the demonstrator was ambiguous.
The ready stance must not be split into invented repetitions. The local audit is
`outputs/online-form-data/review/block-followup-status.json`.

Spiking limitations remain: one Olympic positive cuts near landing; the Wicked
fault examples contain freeze/slow-motion editing and possible alternate-angle
replays. The labels cover the demonstrated technique, not every aspect of a
complete spike. No inference about unseen athletes follows from fitting them.

## Artifacts and reproduction

Local source videos, timestamped review sheets, label evidence, extraction
provenance, original/alternate quality values and candidate models are under
`outputs/online-form-data/`. The frozen corpus is `corpus-required13/`; its
`provenance.json` records the source bundle hashes and limitations. Current
training results are in `models-required13/status.json`, with per-skill TFLite
files and normalization metadata in each skill folder.

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/form-classifier/all_skills.py --data outputs/online-form-data/corpus-required13 --out outputs/online-form-data/models-required13 --train --development-only-fit --quality-field feature_usable_frac --epochs 120
```

Exit code 2 is expected while source diversity or skill coverage remains
insufficient. Each exported candidate is checked with actual TFLite inference
using its published normalization, tensor shape and two-class output contract.
All candidates retain `deployment_approved: false` and `accuracy: null`.

These artifacts are **not installed in Android**. Android's deployed receiving
model still has its narrower six-feature receiving-ready posture contract; its
historical 91.67% development result does not apply to these 32-frame,
24-feature candidates or to the other skills. Integrating and validating these
candidates, collecting independent coach-reviewed examples and completing
blocking training remain outstanding.
