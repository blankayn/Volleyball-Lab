# Pose model asset

The app expects a MediaPipe Pose Landmarker task bundle at:

```
app/src/main/assets/pose_landmarker_full.task
```

The roughly 9 MB model is not committed to Git. Android builds run
`:app:verifyPoseModel` before merging assets. When the file is absent, an online
build downloads Google’s versioned full model and verifies its SHA-256 before
placing it in this directory. Offline builds require the already verified file.
An invalid or missing model fails the build instead of silently shipping a broken
live-session screen.

Official source: https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task

SHA-256: `5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1`

## Which variant to use

Google publishes three bundles of the same model at different capacities. This is
a genuine accuracy-versus-latency tradeoff and it belongs in the evaluation
chapter, since MPJPE is the reported accuracy metric.

| Bundle  | Size    | Notes |
|---------|---------|-------|
| `lite`  | ~3 MB   | Fastest. Visibly worse on fast limb motion, which is most of volleyball. |
| `full`  | ~9 MB   | **Default.** Best balance on mid-range Android hardware. |
| `heavy` | ~29 MB  | Largest; live latency is not yet measured on the target phone. |

The current `full` asset was also checked on the 271-image held-out TUM
Volleyball split. It detected 107 players (39.48%) and reached 17.76 px 2-D
MPJPE on detected frames, or 15.71 px after filtering individual joints by
visibility and image bounds. The `heavy` candidate lowered detected-frame
MPJPE to 15.57 px, but did not improve person detections and reduced reliable
joint coverage. These still-image results do not measure Android latency or
tracking, so the app keeps `full` until a real-device video benchmark supports
a switch. A second run using this exact Android asset reproduced every primary
metric exactly, including a direct recomputation from all detected joint rows.

To deliberately benchmark a different variant, change the pinned URL, checksum,
and filename in `app/build.gradle.kts`; pass the new filename to `MediaPipePoseDetector.create(...)`,
whose `modelAsset` parameter defaults to `DEFAULT_MODEL_ASSET`.

If you benchmark more than one variant for the paper, record which one produced
each MPJPE figure. They are not interchangeable and the difference is large enough
to change a conclusion.

## Do not compress it

`app/build.gradle.kts` sets `androidResources { noCompress += "task" }`. MediaPipe
memory-maps the bundle straight out of the APK; if aapt compresses it, the mapping
fails at runtime with a confusing "Model provided has model identifier ..." error
that looks like a corrupt download rather than a packaging problem. Leave that
line alone.
