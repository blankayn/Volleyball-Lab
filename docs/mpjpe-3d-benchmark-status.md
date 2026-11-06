# 3D MPJPE benchmark status — 5 November 2026

**Measured volleyball-video benchmark: 172.716 mm. General-pose pilot:
143.274 mm. Five-skill volleyball evaluation is still incomplete, and the
manuscript's below-50-mm target has not been achieved on either measured sample.**

This is an actual comparison of the app's frozen full pose-model asset against
independent metric 3D reference annotations. It is not an illustration, agreement
between two monocular AI models, or a conversion of 2D pixel errors to millimetres.

## Volleyball video measurement

The frozen app model was evaluated continuously on the original MADS Volleyball
camera-1 recording in MediaPipe **VIDEO** mode, with the same one-pose and 0.65
detection, presence and tracking thresholds. All finite reference frames were
selected and their hashes saved **before inference**; invalid reference frames
were excluded without consulting predictions.

| Item | Measurement |
|---|---|
| Video | 990 frames, 15 fps, 512×384, one athlete and one laboratory view |
| Valid reference frames | 969; 21 invalid frames excluded per release instructions |
| Joint mapping | 12 corresponding shoulders, elbows, wrists, hips, knees, ankles |
| Reference | Independent 19-joint motion capture, supplied world-to-camera calibration |
| Alignment | Camera axes, each skeleton translated to its hip midpoint; no fitted rotation, scale or per-joint offsets |
| Primary MPJPE | **172.716 mm**, 11,628/11,628 joint pairs |
| Numerical verification | Independently recomputed from the original MATLAB reference, calibration and saved predictions |
| Full-video reproduction | Second run with freshly downloaded references reproduced every saved world/image landmark and visibility value exactly, with matching metric, selection and reference-CSV hashes |
| Pose detection coverage | 990/990 total frames and 969/969 valid reference frames |
| Visibility ≥0.5 diagnostic | **163.611 mm**, 9,659/11,628 joint pairs (83.07%) |
| Software regression | **31** pose-pipeline tests passed, including app-export scope and evidence checks |
| Skill coverage | `volleyball_unspecified`; all five individual skill results remain unavailable |

This is a 12-joint hip-relative protocol, **not** the paper's official 19-joint
tracking protocol with fitted mean per-joint bias correction. The primary score
does not remove difficult or low-visibility finite predictions. Anatomical joint
definitions differ between MediaPipe and motion capture. The sequence includes
calibration poses and transitions, and the source movements use no ball. This
cannot establish unseen-player coaching accuracy, ball-contact accuracy or phone
LIVE_STREAM behavior.

Reproduce using the pinned original camera-1 video, calibration and reference
members. Preparation retrieves only about **144.4 MB** of compressed archive
members (roughly 138 MiB), enforces a transfer budget, and refuses changed files.
It uses the complete public share links published by the authors; no login or
credential is required. If the public host changes or refuses partial access,
preparation fails without downloading the full archive.

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe -m pip install -r tools/pose-pipeline/requirements-benchmark.txt
& tools/pose-pipeline/.venv/Scripts/python.exe tools/pose-pipeline/prepare_mads_benchmark.py --out outputs/mpjpe-mads-volleyball-20261105 --max-transfer-mb 150
& tools/pose-pipeline/.venv/Scripts/python.exe tools/pose-pipeline/benchmark_mads.py --data outputs/mpjpe-mads-volleyball-20261105 --model app/src/main/assets/pose_landmarker_full.task --out outputs/my-mads-video-run
& tools/pose-pipeline/.venv/Scripts/python.exe tools/pose-pipeline/verify_mads_benchmark.py --data outputs/mpjpe-mads-volleyball-20261105 --run outputs/my-mads-video-run
```

The output directory must be new. The run saves the pre-inference selection,
input/model hashes, original-frame predictions, reference CSV, metric and coverage
reports, and visual reference/prediction overlays. Evidence:
`outputs/mpjpe-mads-volleyball-20261105/full-model-video-run/`.
The verification command independently recomputes the result from the original
MATLAB references and raw saved predictions, without using the generated CSV or
the producer's transformation/mapping functions. It confirmed **172.7160280886 mm**.

Acquisition was checked with a fresh reference-only download:
`--references-only --max-transfer-mb 1` transferred **401,536 archive bytes**.
The complete existing RGB/reference set also passed pinned hash/CRC verification
with **zero additional archive bytes**. No second copy of the video was fetched.
Logs: `outputs/mpjpe-access-20261105/mads-preparation-reproduction.log`,
`mads-full-cache-verification.log`, `mads-maintained-verification.log` and
`mads-complete-regression.log` in that same directory.
The second continuous-video run is retained under
`outputs/mpjpe-mads-reproduction-20261105/full-model-video-run/`, including
`verification.json` and `reproduction-check.json`. Its RGB file reuses the
hash-verified original video through a local hard link; references were downloaded
fresh. Both primary and visibility-filtered reports match the initial run exactly.

## Using the MADS result in the app

MADS is the selected offline volleyball benchmark. Its verified, non-personal
summary is packaged as `app/src/main/assets/pose_benchmark.json`, including the
pose-asset hash, source-report hash, selection hash, protocol and coverage. Export
rechecks the original references and saved predictions before writing this file:

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe tools/pose-pipeline/export_mads_benchmark.py --data outputs/mpjpe-mads-volleyball-20261105 --run outputs/mpjpe-mads-volleyball-20261105/full-model-video-run --evaluated-on 2026-11-05 --out app/src/main/assets/pose_benchmark.json
```

The technique-assessment card provides **About 3D accuracy**, which displays
172.7 mm, the sample size, and its offline, single-athlete scope. The session row
continues to say that the individual recording's 3D error is not measured.
Dataset benchmark metadata is not written into player technique scores,
confidence, session history or skill accuracy percentages. The Android
`verifyPoseModel` build task rejects benchmark metadata for a different pose
asset or an unsupported scope.

App integration checks on 5 November passed: **31** pose-pipeline tests, debug
APK assembly, Android lint (**0 errors**, 187 warnings and 8 hints across the
current project), and a negative build-task check that rejected a copied summary
with a different model hash. The real app asset was not modified by that check.
The built APK's summary matched the exported JSON byte for byte, and its packaged
pose asset matched the measured model's SHA-256. Evidence is retained in
`outputs/mpjpe-access-20261105/mads-app-build-short-temp.log`,
`mads-reject-mismatched-metadata.log` and `mads-apk-verification.json`.
No Android device was connected, so installation and visual interaction with the
new dialog on a physical phone remain untested. The local Gradle run used the
same short Java temporary-directory setting documented by `tools/verify-app.ps1`
to avoid the Windows Java NIO loopback-path error.

## General-pose pilot protocol and results

| Item | Measurement |
|---|---|
| Dataset | Official MPI-INF-3DHP test, sequence TS5 |
| Selection | 20 uniformly spaced official valid frames, fixed before inference |
| Reference | Original camera-coordinate `annot3`, millimetres; **not** `univ_annot3` |
| Joints | 12 corresponding shoulders, elbows, wrists, hips, knees and ankles |
| Model | App `pose_landmarker_full.task`, SHA-256 `5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1` |
| Runtime | Python MediaPipe 1.0.1, IMAGE mode, one pose, 0.65 detection/presence thresholds |
| Alignment | Each skeleton translated to its hip midpoint; no rotation, scaling or Procrustes fitting |
| Primary MPJPE | **143.274 mm**, 240/240 joint comparisons, 20/20 poses detected |
| Visibility ≥0.5 diagnostic | **116.784 mm**, 205/240 joints (85.42% coverage) |
| Independent numerical recomputation | 143.2741152375 mm from original annotations and saved predictions |
| Reproduction | New preparation/evaluation CLI run reproduced 143.274 mm with matching image, selection, model and reference-CSV hashes |
| Regression checks | 21 pose-pipeline tests passed |

The primary metric retains low-visibility finite predictions rather than hiding
their errors. Missing detections must be reported as coverage, never zero error.
The diagnostic's smaller error excludes 35 joints and is not the primary result.

Limitations: one sequence and sparse still images; no confidence interval for
unseen-player generalization; approximate correspondence between MediaPipe
landmarks and reference joint centres; unknown overlap with MediaPipe's original
pretraining data. This is not the official 17-joint universal-skeleton MPI-INF-3DHP
protocol, Android LIVE_STREAM validation, or a volleyball per-skill result.
The evaluator reports the general category separately and leaves the five-skill
target unavailable. No per-session MPJPE was invented for student videos.

## Reproduce without downloading the full dataset

The official archive is **6,936,910,586 bytes**. Exact HTTP range requests let us
retrieve only metadata and the selected images. The initial index/metadata/image
requests total approximately **31.3 MB**; the reproduction reused verified images
and transferred another **3.51 MB**. The helper refuses servers that ignore range
requests and enforces a transfer budget. ZIP member CRCs, file hashes and the
pre-inference selection are retained. All dataset material stays in ignored
outputs and is used only for non-commercial academic evaluation.

```powershell
& tools/pose-pipeline/.venv/Scripts/python.exe -m pip install -r tools/pose-pipeline/requirements-benchmark.txt
& tools/pose-pipeline/.venv/Scripts/python.exe tools/pose-pipeline/prepare_mpi3dhp_benchmark.py --out outputs/my-mpjpe-pilot --sequence TS5 --frames 20 --max-transfer-mb 32
& tools/pose-pipeline/.venv/Scripts/python.exe tools/pose-pipeline/benchmark_mpi3dhp.py --selection outputs/my-mpjpe-pilot/selection.json --annotations outputs/my-mpjpe-pilot/annotations.mat --images outputs/my-mpjpe-pilot/images --model app/src/main/assets/pose_landmarker_full.task --out outputs/my-mpjpe-pilot/full-model-run
```

Use a new evaluation-output directory; measured runs are not overwritten.
Preparation validates reusable cached files and refuses a changed frame selection.

Local evidence:

- `outputs/mpjpe-3dhp-pilot-20261105/full-model-run/report.json`
- `outputs/mpjpe-3dhp-pilot-20261105/full-model-run/independent-recomputation.json`
- `outputs/mpjpe-3dhp-reproduction-20261105/full-model-run/report.json`
- `outputs/mpjpe-access-20261105/complete-regression.log`

## Volleyball reference-data follow-up

[SportsPose](https://christianingwersen.github.io/SportsPose/download.html)
requires an academic access request. A local draft has been prepared using the
user-provided name and university; the user has no academic email, so the draft
asks whether university/adviser confirmation is acceptable. With explicit user
authorization, the request was sent on 5 November 2026 to the contact published
by SportsPose, and Gmail read-back verified its SENT status. No recordings were
attached. The receipt and personal details remain in ignored local outputs.
The requested subset must include RGB, raw metric 3D joints, calibration, timing,
joint definitions and a description of which volleyball skills it actually covers.

[EgoHumans](https://rawalkhirodkar.github.io/egohumans/) documents volleyball
with multi-view 3D references. Its official Drive folder lists the first volleyball
archive at approximately 14.89 GB (13.87 GiB) in `.tar.gz` format. A follow-up
found sequence 005 at approximately **2.20 GB**, but bounded prefix probes of 005
and alternates 003 and 004 returned the public host's download-quota error. No complete
archive was downloaded. The separate small COCO benchmark annotations contain
2D tagging/lego/fencing data, not volleyball metric 3D references. See the
[source-coverage audit](mpjpe-volleyball-reference-coverage.md).

[MADS](https://visal.cs.cityu.edu.hk/research/mads/) documents synchronized
multi-view sports recordings, calibration and 19-joint motion-capture references.
The initial incomplete Drive URL failed; the complete public resource-key link
published by the authors works anonymously. Exact ZIP-member byte ranges retrieved
one 144.5 MB volleyball camera recording, its 19-joint motion-capture reference
and camera calibration, rather than the complete multi-gigabyte archive. Member
CRCs and SHA-256 hashes are retained in ignored local provenance files.

The original camera-1 video has 990 synchronized frames at 15 fps and 512×384
resolution. Twenty-one reference frames contain NaNs and are excluded as the
authors require. Supplied Rodrigues extrinsics project reference joints onto
the actor in sampled images across the recording; no rotation or scale is fitted
to MediaPipe predictions. The paper reports metric errors in millimetres and the
release's evaluation code uses raw coordinate differences.

The authors describe these sports as demonstrations **without balls or rackets**.
The [authors' paper](http://visal.cs.cityu.edu.hk/static/pubs/journal/ivc17-mads.pdf)
describes the collection on page 4 and metric results on pages 12–13.
The recording has a volleyball sequence label, but does not provide separate
Serving, Receiving, Setting, Spiking and Blocking frame annotations. It must
therefore remain `volleyball_unspecified` unless an independently documented
action annotation is supplied. It cannot establish ball-contact accuracy or
five-skill coverage.

An external general-pose pilot is useful evidence, but does not replace actual
Serving, Receiving, Setting, Spiking and Blocking reference recordings. The
active objective is not complete until the intended volleyball scope is evaluated
and its results and coverage are reported, including failure to meet the target.

## Completion audit and remaining dependency

| Requirement | Current authoritative evidence | Status |
|---|---|---|
| Independent metric 3D references | Pinned original MADS motion capture and camera calibration, verified sizes/CRCs/SHA-256 | Complete for the measured sequence |
| App pose-asset identity | SHA-256 recorded in both run reports, selection and prediction files | Verified for Python VIDEO inference |
| Reproducible numerical MPJPE | Two matching full-video runs, direct raw-MAT recomputation and `reproduction-check.json` | Complete for the measured sequence |
| Error and missing-data coverage | 172.716 mm, 969 valid reference frames, 11,628 joint pairs; 21 invalid reference frames excluded | Reported without hiding low-visibility finite predictions |
| Serving reference intervals and separate result | `by_skill.serve`: zero joint pairs, null MPJPE | Incomplete |
| Receiving reference intervals and separate result | `by_skill.pass`: zero joint pairs, null MPJPE | Incomplete |
| Setting reference intervals and separate result | `by_skill.set`: zero joint pairs, null MPJPE | Incomplete |
| Spiking reference intervals and separate result | `by_skill.spike`: zero joint pairs, null MPJPE | Incomplete |
| Blocking reference intervals and separate result | `by_skill.block`: zero joint pairs, null MPJPE | Incomplete |
| Manuscript below-50-mm claim | Both measured samples exceed 50 mm; five-skill target is null | Unsupported; target not achieved on measured samples |

The remaining dependency is access to synchronized RGB and independent metric
3D references that actually cover the missing actions, followed by documented
action intervals and actor identities. Merely renaming the MADS sequence as five
skills would not supply that evidence. A 5 November read-back of the SportsPose
request thread contained only the sent request and no reply. Three bounded
EgoHumans sequence-prefix attempts returned the host's explicit quota refusal;
no complete archive was downloaded. Source access and action coverage must be
resolved before the full objective can be marked complete. No more software
tests or repeated runs of the same sequence can substitute for these references.

## Dataset references

MADS: Zhang, W., Liu, Z., Zhou, L., Leung, H., and Chan, A. B. (2017).
*Martial Arts, Dancing and Sports Dataset: a Challenging Stereo and Multi-View
Dataset for 3D Human Pose Estimation*. Image and Vision Computing, 61, 22–39.
[Paper and release](https://visal.cs.cityu.edu.hk/research/mads/),
[DOI](https://doi.org/10.1016/j.imavis.2017.02.002).

[Official MPI-INF-3DHP release](https://vcai.mpi-inf.mpg.de/3dhp-dataset/).
The toolkit's research-only licence and README are retained with the downloaded
data. Required scientific citations:

- Mehta et al., *Monocular 3D Human Pose Estimation In The Wild Using Improved CNN Supervision*, 3DV 2017.
- Mehta et al., *VNect: Real-time 3D Human Pose Estimation With A Single RGB Camera*, ACM Transactions on Graphics / SIGGRAPH 2017.
