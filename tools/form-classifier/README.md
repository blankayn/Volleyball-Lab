# Form classifier

Trains the small classifier that reads BlazePose landmarks and judges a rep.

```bash
../pose-pipeline/.venv/Scripts/python train.py --skill pass --out models
```

TensorFlow lives in the pose-pipeline venv rather than a second 600 MB copy here.
The `--data` default is a sibling hop to `../pose-pipeline/data`, so it resolves the
same on any machine that has the repo.

## Where this sits

```
video files
    ↓  ../pose-pipeline/extract.py        ← BlazePose, cuts, ✓/✗ labels
../pose-pipeline/data/*_sequences.npz      (segments, 32, 24)
    ↓  train.py                           ← this folder
models/form_classifier.tflite              11 KB, ships beside the pose model
models/form_classifier.json                classes + normalisation constants
```

`form_classifier.json` is not optional. The app must apply the same per-feature
mean and standard deviation before calling the model; feeding it raw angles
produces confident nonsense rather than a visible error.

## Two different models — don't confuse them

**BlazePose** is pretrained, frozen, and already in `app/src/main/assets/`. It is
never trained here. It turns pixels into 33 landmarks.

**The form classifier** is what `train.py` produces. It reads those landmarks and
answers "was this rep correct?".

## About label_clips.py

`label_clips.py` (already in this folder) cuts clips by hand-logged timestamps
into `trimmed_clips/<action>/`, with labels like `serve`, `spike`, `set`, `dig`,
`block`. That builds an **action classifier**: *which skill is this?*

The app already knows which skill it is — the player picks the drill before the
camera opens. So an action classifier answers a question the app never asks,
while the question it does ask — *was that pass any good, and why not?* — needs
correct/faulty labels instead.

`train.py` takes its class list from the data rather than hardcoding it, so it
trains either kind. Point it at badge-labelled segments and you get a form model;
point it at manually labelled skills and you get an action model. Nothing needs
to change but the input.

Two notes if you do run `label_clips.py`: it expects `.mp4` files in
`source_videos/`, which does not exist yet, and the `videos_sample/` folder here
is the CVPR broadcast dataset — loose JPEG frames from two matches, no
annotations, players about 100 px tall. It cannot feed either pipeline.

## Current state — read this before quoting any number

The last run:

```
samples : 9   shape (32, 14)
classes : fault=4, ok=5
clips   : 1 (serve_receive)
params  : 2,018
```

Training accuracy was 1.000 and **that is not a result.** Nine segments from one
sixteen-second clip means one athlete, one gym, one session. The model can
memorise that in a few thousand parameters, and it did.

`train.py` refuses to print a headline accuracy below three distinct source
clips, and writes the reason into `models/form_classifier.json` instead of a
number. It splits by source clip and never randomly — two segments from the same
video share an athlete and a camera, so a random split measures memorisation and
reports it as accuracy.

The exported `.tflite` loads and runs correctly. What exists is a working,
verified pipeline with placeholder weights.

## What makes it a real result

Roughly 100 labelled segments for receive, from 15–20 different clips, is the
point where a held-out accuracy starts to mean something. Around 250–300 reps
per skill, with several athletes, is where it becomes defensible.

Re-run `extract.py` over more clips, then this script. Nothing here changes.
