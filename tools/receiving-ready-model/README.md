# Receiving-ready posture model

The TensorFlow model the app ships as
`app/src/main/assets/receiving_ready_posture.tflite`, with the script, data and
evidence that produced it.

It answers one question, during forearm passing (Receiving), from the front view:
**are the knees bent enough, or is the athlete standing too upright?** It does not
judge the other four skills.

The app runs it in `ReceivingReadyPostureModel.kt`.

## What is here

| Folder | Contents |
| --- | --- |
| `train_receiving_ready_tf.py` | The training script. |
| `data/` | The training table (v29: 48 clips, one row per clip) and the earlier v25/v27 tables the script also reads. Also the pose-accuracy (MPJPE) reports and the spent v29 check it records. |
| `model/` | What the script produced: the `.tflite` the app ships, the `.keras` source, the training report and the held-out predictions. |
| `validation-v35/` | The separate test on new videos, run after training (`RELEASE_READINESS_V35.md` summarises it). |

The SHA-256 of `model/receiving_ready_posture.tflite` is
`ab47e1c2e7c0fbb634dec4d8424524cdf3a7eb56a4e7f77ff006eb765acba251`, identical to
the app asset.

## How it works

- **Inputs:** six numbers per clip, in this order: knee angle (median, 10th and 90th
  percentile) and forward trunk lean (median, 10th and 90th percentile), over 32
  resampled pose frames.
- **Model:** class-balanced logistic regression trained with TensorFlow, exported as a
  single Dense layer with sigmoid output. Output is the probability of a fault.
- **Decision in the app:** OK below 0.40, fault at 0.60 or above, "uncertain" in
  between.
- **Testing:** leave-one-video-source-out, with scaling fitted inside each fold, so
  no clip is tested on data from its own video.

## Results to quote

Use the separate test on new videos (`validation-v35/`), not the training report:

| Measure | Result |
| --- | --- |
| Correct decisions | 13 of 14 (92.86%) |
| 95% interval | 68.5% to 98.7% |
| Balanced accuracy | 90.0% |
| Clips that got a decision | 14 of 20; 6 failed the pose-quality check |
| Correct, counting all 20 clips | 13 of 20 (65%) |

The 91.67% in `model/training_result.txt` used the same data to choose the
threshold, so it is not a fair test.

## Feature parity with the app

On 17 November 2026 the trunk-lean and knee-angle features were recomputed from 84
stored training clips with the app-matching code in `tools/pose-pipeline/features.py`.
They matched what training used to a median of 0.4 degrees.

## Rerunning

Requires Python with TensorFlow, numpy, pandas and scikit-learn. On the original PC,
TensorFlow fails to import in `tools/pose-pipeline/.venv` with a DLL error, so that
environment needs repairing first. From this folder:

```bash
python train_receiving_ready_tf.py
```

It rewrites `model/`. Training is deterministic; compare the new `.tflite` hash with
the one above before replacing the app asset.

`validation-v35/score_v35.py` is kept as a record of how the test was scored. It
still refers to the original working folder and its video clips, which are not
copied here.
