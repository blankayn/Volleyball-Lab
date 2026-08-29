"""Turns raw pose landmarks into the two representations the model actually eats.

MediaPipe hands back 33 landmarks per frame in image space, which is the wrong
coordinate system for comparing one athlete's rep to another's: move the phone a
metre closer and every number changes, though the form did not. Everything here
exists to remove the camera from the measurement.

Two representations come out, because they answer different questions:

- **Normalised landmarks** keep the full body shape. Use them for MPJPE, for
  drawing, and as the input to a sequence model that should learn its own
  features.
- **Joint angles** collapse each frame to fourteen numbers. They train on far
  less data, and - the reason they matter for this project - a threshold on one
  of them is a sentence a coach would say. "Elbow at 142 degrees at contact,
  target 170+" is feedback. A softmax score is not.

Prefer angles while the dataset is small. Revisit landmarks once there are
thousands of labelled reps.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

# MediaPipe Pose landmark indices, named so the geometry below reads as anatomy
# rather than as array offsets.
NOSE = 0
L_SHOULDER, R_SHOULDER = 11, 12
L_ELBOW, R_ELBOW = 13, 14
L_WRIST, R_WRIST = 15, 16
L_HIP, R_HIP = 23, 24
L_KNEE, R_KNEE = 25, 26
L_ANKLE, R_ANKLE = 27, 28
L_HEEL, R_HEEL = 29, 30
L_FOOT, R_FOOT = 31, 32

NUM_LANDMARKS = 33

# Below this torso length in pixels the normalisation divisor is mostly noise and
# every derived number inherits that noise. Roughly a 130 px tall person, which is
# also about where BlazePose stops being trustworthy on limbs.
MIN_TORSO_PX = 40.0


@dataclass
class Normalised:
    """One frame, with the camera divided out.

    ``coords`` is (33, 2) in torso-lengths from the hip midpoint. +x is screen
    right; +y is *up*, which is the opposite of image convention and matches how
    a person would describe a pose out loud.
    """

    coords: np.ndarray
    visibility: np.ndarray
    torso_px: float
    valid: bool


def to_pixels(landmarks: np.ndarray, width: int, height: int) -> np.ndarray:
    """Scales MediaPipe's per-axis [0,1] coordinates into square pixels.

    MediaPipe normalises x by width and y by height independently, so on a
    720x1280 portrait clip a circle in the world is an ellipse in those numbers.
    Every distance and angle downstream would be wrong by the aspect ratio - and
    wrong by a *different* factor for portrait phone footage than for landscape
    broadcast, which is exactly the mix this project has. Convert once, here.
    """
    out = np.empty((len(landmarks), 2), dtype=np.float64)
    out[:, 0] = landmarks[:, 0] * width
    out[:, 1] = landmarks[:, 1] * height
    return out


def normalise(pixels: np.ndarray, visibility: np.ndarray) -> Normalised:
    """Re-expresses a frame relative to the athlete's own body.

    Origin is the hip midpoint and the unit is torso length (hip midpoint to
    shoulder midpoint). That choice removes camera distance and, because torso
    length varies far less between people than limb length does, most of the
    difference between a tall athlete and a short one performing the same rep.

    It does not remove camera *angle*. A side-on rep and a front-on rep normalise
    to genuinely different numbers, which is why the collection protocol has to
    fix the angle rather than hope the maths absorbs it.
    """
    hip = (pixels[L_HIP] + pixels[R_HIP]) / 2.0
    shoulder = (pixels[L_SHOULDER] + pixels[R_SHOULDER]) / 2.0
    torso = float(np.linalg.norm(shoulder - hip))

    if not np.isfinite(torso) or torso < MIN_TORSO_PX:
        return Normalised(np.zeros((NUM_LANDMARKS, 2)), visibility, torso, valid=False)

    coords = (pixels - hip) / torso
    coords[:, 1] *= -1.0  # image y grows downward; flip so +y is up
    return Normalised(coords, visibility, torso, valid=True)


def _angle(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    """Interior angle at b, in degrees, or NaN if a segment has no length."""
    ba, bc = a - b, c - b
    na, nc = np.linalg.norm(ba), np.linalg.norm(bc)
    if na < 1e-9 or nc < 1e-9:
        return float("nan")
    cosine = float(np.dot(ba, bc) / (na * nc))
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def _from_vertical(a: np.ndarray, b: np.ndarray) -> float:
    """Tilt of segment a->b away from straight up, in degrees. 0 is upright."""
    v = b - a
    if np.linalg.norm(v) < 1e-9:
        return float("nan")
    return math.degrees(math.atan2(abs(v[0]), abs(v[1])))


def _from_horizontal(a: np.ndarray, b: np.ndarray) -> float:
    """Tilt of segment a->b away from level, in degrees. 0 is flat."""
    v = b - a
    if np.linalg.norm(v) < 1e-9:
        return float("nan")
    return math.degrees(math.atan2(abs(v[1]), abs(v[0])))


# Order is fixed and load-bearing: it is the feature vector's column order, so
# appending is safe and reordering silently invalidates every model trained
# before the change.
ANGLE_NAMES = (
    "elbow_l", "elbow_r",
    "shoulder_l", "shoulder_r",
    "hip_l", "hip_r",
    "knee_l", "knee_r",
    "torso_lean",
    "platform_tilt",
    "platform_height",
    "stance_width",
    "knee_flexion_mean",
    "weight_forward",
)


def angles(n: Normalised) -> np.ndarray:
    """The fourteen numbers a coach would actually comment on.

    Most are plain joint angles. Four are volleyball-specific and worth naming:

    - ``platform_tilt`` - the forearm platform's angle off level, measured elbow
      midpoint to wrist midpoint. The forearm-pass fault that matters most.
    - ``platform_height`` - wrist midpoint height in torso-lengths above the
      hips. Positive means the hands have come up above the waist, the classic
      "platform above the shoulders" error.
    - ``stance_width`` - ankle separation in torso-lengths. Distinguishes a split
      ready stance from feet-together.
    - ``weight_forward`` - how far the shoulder midpoint sits ahead of the ankle
      midpoint. Negative is leaning back on the heels, which is the reason a
      passer is late to the ball.
    ``knee_flexion_mean`` is a known dud and is kept only because removing a
    column would invalidate anything already trained. On the sample clip it
    separates the classes at 0.09 pooled standard deviations while the individual
    knees manage 0.87 and 0.69 - in *opposite* directions, because the correct
    rep bends the left knee and the faulty one the right. Averaging cancels that;
    so does taking the absolute difference, which was tried and scored 0.16.

    The signal is which leg is forward, and reading it needs the skeleton
    mirror-normalised so the legs are ordered by stance rather than by side. That
    is worth doing, and it needs footage of athletes filmed from both sides to
    validate - with one demonstrator there is no way to tell stance information
    apart from which way this particular person happened to face.
    """
    if not n.valid:
        return np.full(len(ANGLE_NAMES), np.nan)

    c = n.coords
    shoulder_mid = (c[L_SHOULDER] + c[R_SHOULDER]) / 2.0
    hip_mid = (c[L_HIP] + c[R_HIP]) / 2.0
    elbow_mid = (c[L_ELBOW] + c[R_ELBOW]) / 2.0
    wrist_mid = (c[L_WRIST] + c[R_WRIST]) / 2.0
    ankle_mid = (c[L_ANKLE] + c[R_ANKLE]) / 2.0

    knee_l = _angle(c[L_HIP], c[L_KNEE], c[L_ANKLE])
    knee_r = _angle(c[R_HIP], c[R_KNEE], c[R_ANKLE])

    return np.array([
        _angle(c[L_SHOULDER], c[L_ELBOW], c[L_WRIST]),
        _angle(c[R_SHOULDER], c[R_ELBOW], c[R_WRIST]),
        _angle(c[L_ELBOW], c[L_SHOULDER], c[L_HIP]),
        _angle(c[R_ELBOW], c[R_SHOULDER], c[R_HIP]),
        _angle(c[L_SHOULDER], c[L_HIP], c[L_KNEE]),
        _angle(c[R_SHOULDER], c[R_HIP], c[R_KNEE]),
        knee_l,
        knee_r,
        _from_vertical(hip_mid, shoulder_mid),
        _from_horizontal(elbow_mid, wrist_mid),
        float(wrist_mid[1]),
        float(abs(c[L_ANKLE][0] - c[R_ANKLE][0])),
        float(np.nanmean([knee_l, knee_r])),
        float(shoulder_mid[0] - ankle_mid[0]),
    ], dtype=np.float64)


def resample(sequence: np.ndarray, length: int) -> np.ndarray:
    """Stretches or squashes a sequence onto a fixed number of steps.

    Two reps of the same skill take different amounts of time, and the same rep
    shot at 60 fps has twice the rows of one shot at 30. A model fed raw rows
    would learn frame rate. Linear interpolation on the time axis removes both
    problems at once, and is why clips from a phone and clips pulled off YouTube
    can sit in the same training set.
    """
    if sequence.ndim == 1:
        sequence = sequence.reshape(-1, 1)
    if len(sequence) == 0:
        return np.full((length, sequence.shape[1]), np.nan)
    if len(sequence) == 1:
        return np.repeat(sequence, length, axis=0)

    src = np.linspace(0.0, 1.0, len(sequence))
    dst = np.linspace(0.0, 1.0, length)
    out = np.empty((length, sequence.shape[1]), dtype=np.float64)
    for col in range(sequence.shape[1]):
        out[:, col] = np.interp(dst, src, sequence[:, col])
    return out
