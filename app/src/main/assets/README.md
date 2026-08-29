# Pose model asset

The app expects a MediaPipe Pose Landmarker task bundle at:

```
app/src/main/assets/pose_landmarker_full.task
```

It is **not committed**. The file is around 9 MB of binary weights, which does not
belong in a git history, so `.gitignore` excludes `*.task` from this directory.
Without it the capture screen shows "Pose model not installed" rather than
crashing.

## Install it

From the repository root:

```bash
curl -L -o "app/src/main/assets/pose_landmarker_full.task" https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task
```

Then rebuild. No code change is needed — `MediaPipePoseDetector` checks for the
asset at startup and reports its absence as a specific, actionable error.

## Which variant to use

Google publishes three bundles of the same model at different capacities. This is
a genuine accuracy-versus-latency tradeoff and it belongs in the evaluation
chapter, since MPJPE is the reported accuracy metric.

| Bundle  | Size    | Notes |
|---------|---------|-------|
| `lite`  | ~3 MB   | Fastest. Visibly worse on fast limb motion, which is most of volleyball. |
| `full`  | ~9 MB   | **Default.** Best balance on mid-range Android hardware. |
| `heavy` | ~29 MB  | Most accurate. Too slow for live overlay on mid-range phones. |

Swap the variant by changing `full` to `lite` or `heavy` in both the URL above and
the filename, then pass the new filename to `MediaPipePoseDetector.create(...)`,
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
