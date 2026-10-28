"""Geometry that matches the Android app's, so offline numbers mean something on the phone.

This is a deliberate port of `app/src/main/java/.../pose/analysis/Angles.kt` and the
measurement helpers in `SkillAnalyzer.kt` / `SkillAnalyzers.kt`. Every function
below mirrors one there, with the same formula, the same axes, the same sign
conventions and the same degenerate behaviour. `test_parity.py` asserts that
against the Kotlin unit tests' own vectors.

## Why it is a port rather than an independent implementation

This module previously measured angles in **image space** - normalised landmarks
scaled to pixels and divided by torso length. That is a perspective projection,
so the same elbow bend measures differently depending on where the athlete stands
and how they are turned. The app has always used MediaPipe's **world** landmarks:
metric, hip-centred, real depth.

Both produce plausible degrees. Measured against each other on 143 clean frames of
real passing footage they disagreed by 23 degrees on average, 70% of measurements
were off by more than 10, and elbow angles diverged by up to 128. A threshold
derived from the old numbers would have been silently and badly wrong once it
reached `SkillAnalyzer`, and the symptom would have been "the app disagrees with
our own data" long after the cause was forgettable.

Every column here now mirrors a measurement the app already makes, so a threshold
learned from this data is expressible as a `FormCue` target range without
translation. Features with no Kotlin counterpart were removed rather than kept:
a number the app cannot compute is a number the app cannot act on.

## Conventions inherited from the Kotlin, all easy to get backwards

- **World y increases downward**, matching image convention. Upward motion is
  negative. Four places compensate and they do it differently:
  `from_vertical_between` and `trunk_forward_lean` negate explicitly;
  `hip_rise_metres` and `hands_above_nose_metres` get it from subtraction order;
  `shoulder_tilt` does not compensate at all and is only correct because it
  returns an absolute value.
- **World z decreases toward the camera.** Compensated in `trunk_forward_lean`
  and `penetration_metres`. `facing_camera` needs none - it takes an absolute
  difference.
- **atan2 argument order differs by intent.** Measuring *from vertical* passes
  the in-plane horizontal first (`atan2(dx, up)`); measuring *from horizontal*
  passes the conventional order (`atan2(dy, dx)`). Swapping them silently yields
  the complement, which looks like a plausible angle.
- **NaN is Kotlin's null.** The app returns `Float?` and its analyzers treat null
  as "unmeasurable", never as zero - a missing angle scored as 0 reads as a
  catastrophic rep rather than as no data.
- **One exception to that:** `facing_camera` returns 0.0, not NaN, on a
  degenerate shoulder line, because the Kotlin returns a non-nullable `0f` and
  gates read `facing > 0.9`. NaN happens to take the same branch today; 0.0
  makes the equivalence structural rather than accidental.

## Image space is still used, for exactly one thing

`vertical_scale` and everything built on it. World landmarks are hip-centred by
construction, so the hips sit at the origin in every frame whether the athlete is
standing or at the top of a jump; world space cannot answer "how high did they
get". Image space records the jump but in frame-fractions that vary with camera
distance. Their ratio is a per-frame calibration.

Note that the app compares image landmarks in **raw normalised units, not
aspect-corrected pixels** - `Angles.distance2D` mixes an x normalised by width
with a y normalised by height in one Euclidean norm. That is an uncorrected
aspect assumption, and this module reproduces it deliberately: "fixing" it here
would break parity with the phone, which is the only thing that matters.
"""
from __future__ import annotations

import math

import numpy as np

# MediaPipe Pose landmark indices. BodyPoint's ordinals in the Kotlin are defined
# to *be* these indices, so the two lists cannot drift apart silently.
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

# Angles.EPSILON. Same value, same role: below it a segment has no direction and
# the measurement is unmeasurable rather than zero.
EPSILON = 1e-6

# Angles.MIN_FACING_EVIDENCE. The least nose-to-shoulder-line offset, in metres,
# that resolves which way the athlete faces. Measured p0.1 of the real
# distribution is 0.011 m and the median 0.162 m, so this rejects genuine
# ambiguity and little else - while preventing a silent 180-degree inversion.
MIN_FACING_EVIDENCE = 0.02

LEFT, RIGHT = 0, 1


# --- Ports of Angles.kt ---------------------------------------------------

def angle_at(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    """Interior angle at vertex b, in degrees. Mirrors `Angles.at`.

    Returns 0..180, where a fully straight limb is 180 rather than 0 - which
    trips people up, because an elbow reading 175 sounds like almost nothing
    until you remember 180 is the ceiling. NaN when either segment is
    degenerate, which is how the app's null is represented here.
    """
    ab, cb = a - b, c - b
    ab_len, cb_len = float(np.linalg.norm(ab)), float(np.linalg.norm(cb))
    if ab_len < EPSILON or cb_len < EPSILON:
        return float("nan")
    cosine = float(np.dot(ab, cb)) / (ab_len * cb_len)
    return math.degrees(math.acos(max(-1.0, min(1.0, cosine))))


def midpoint(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    return (a + b) / 2.0


def hip_centre(world: np.ndarray) -> np.ndarray:
    return midpoint(world[L_HIP], world[R_HIP])


def shoulder_centre(world: np.ndarray) -> np.ndarray:
    return midpoint(world[L_SHOULDER], world[R_SHOULDER])


def hip_centre_image(image: np.ndarray) -> np.ndarray:
    return midpoint(image[L_HIP], image[R_HIP])


def shoulder_centre_image(image: np.ndarray) -> np.ndarray:
    return midpoint(image[L_SHOULDER], image[R_SHOULDER])


def distance(a: np.ndarray, b: np.ndarray) -> float:
    """Straight-line 3D distance. Mirrors `Angles.distance`."""
    return float(np.linalg.norm(a - b))


def distance_2d(a: np.ndarray, b: np.ndarray) -> float:
    """Distance ignoring depth. Mirrors `Angles.distance2D`."""
    return math.hypot(a[0] - b[0], a[1] - b[1])


def from_vertical_between(a: np.ndarray, b: np.ndarray) -> float:
    """Tilt of a->b away from straight up, in degrees. Mirrors `Angles.fromVerticalBetween`.

    0 points straight up; positive leans toward the athlete's right. Frontal
    plane only - depth is ignored, which is why a separate sagittal measurement
    exists for the cues that actually mean "forward".
    """
    dx = b[0] - a[0]
    dy = -(b[1] - a[1])  # world y grows downward; negate so up reads as 0
    if abs(dx) < EPSILON and abs(dy) < EPSILON:
        return float("nan")
    return math.degrees(math.atan2(dx, dy))


def trunk_lateral_lean(world: np.ndarray) -> float:
    """Sideways trunk lean from vertical. Mirrors `Angles.trunkLateralLean`.

    No cue reads this. It is kept as a diagnostic because it is what the old
    image-space `torso_lean` column was reaching for, and having both makes the
    old and new datasets comparable.
    """
    return from_vertical_between(hip_centre(world), shoulder_centre(world))


def forward_axis(world: np.ndarray) -> np.ndarray | None:
    """The athlete's facing direction as a horizontal unit vector in world x-z.

    Mirrors `Angles.forwardAxis`. World landmarks are hip-centred but their axes
    are the camera's, so camera -z is the athlete's forward only while they face
    the lens. Anything anatomical about depth has to project onto this instead.

    The vector is the horizontal normal to the shoulder line; that leaves two
    candidates 180 degrees apart and the nose picks between them. Returns None
    rather than guessing when the nose projects too close to the shoulder line -
    measured across 2849 real frames the nose sits a median 0.162 m forward and
    under 0.02 m on 0.21% of them.
    """
    left, right = world[L_SHOULDER], world[R_SHOULDER]
    across_x, across_z = right[0] - left[0], right[2] - left[2]
    span = math.hypot(across_x, across_z)
    if span < EPSILON:
        return None

    # Rotate the shoulder line 90 degrees in the horizontal plane.
    normal = np.array([-across_z / span, across_x / span])
    centre, nose = shoulder_centre(world), world[NOSE]
    toward_nose = (nose[0] - centre[0]) * normal[0] + (nose[2] - centre[2]) * normal[1]
    if abs(toward_nose) < MIN_FACING_EVIDENCE:
        return None
    return -normal if toward_nose < 0 else normal


def along_forward(world: np.ndarray, start: np.ndarray, end: np.ndarray) -> float:
    """How far `end` sits ahead of `start` along the athlete's forward direction,
    in metres. Mirrors `Angles.alongForward`."""
    axis = forward_axis(world)
    if axis is None:
        return float("nan")
    return float((end[0] - start[0]) * axis[0] + (end[2] - start[2]) * axis[1])


def trunk_forward_lean(world: np.ndarray) -> float:
    """Forward trunk lean from vertical, in degrees. Mirrors `Angles.trunkForwardLean`.

    Positive leans the way the athlete is facing. This is the sagittal plane, and
    it is the one the coaching cues mean: "stay tall on the serve" and "lean into
    the ball" are both about the chest going forward, which the frontal
    measurement cannot see at all.

    Measured against the athlete's own `forward_axis`, not camera z. Those agree
    only while the athlete faces the lens: on 7274 real frames the camera-relative
    version averaged +15.5 degrees facing the lens and -20.8 facing away, so a
    rear-facing passer scored zero on a cue whose band is entirely positive.
    """
    hip, shoulder = hip_centre(world), shoulder_centre(world)
    forward = along_forward(world, hip, shoulder)
    if not np.isfinite(forward):
        return float("nan")
    up = -(shoulder[1] - hip[1])
    if abs(forward) < EPSILON and abs(up) < EPSILON:
        return float("nan")
    return math.degrees(math.atan2(forward, up))


def shoulder_tilt(world: np.ndarray) -> float:
    """How far the shoulder line is off horizontal, always positive.

    Mirrors `Angles.shoulderTilt`, fold included.

    The fold is what makes this an orientation rather than a direction. World x is
    a camera axis, so the vector from the left shoulder to the right shoulder
    reverses when the athlete turns around; without folding, level shoulders read
    ~0 from behind and ~180 from the front, and the value flips mid-rep. `abs`
    alone does not help - it folds the sign of dy, not of dx.

    This was a real defect in both implementations, found by running this port
    over 7318 frames of real footage: unfolded, the mean was 156.5 degrees and
    only 1.98% of frames landed inside `PassShouldersLevel`'s 0..12 band, so that
    cue failed essentially every rep. Folded: mean 13.8, median 4.4. Fixed in
    `Angles.kt` and here together, with regression tests on both sides.

    Note it still does not compensate for the downward y axis, matching the
    Kotlin - correct only because of the absolute value, and left that way so the
    two stay line-for-line comparable.
    """
    left, right = world[L_SHOULDER], world[R_SHOULDER]
    dx, dy = right[0] - left[0], right[1] - left[1]
    if abs(dx) < EPSILON and abs(dy) < EPSILON:
        return float("nan")
    directed = abs(math.degrees(math.atan2(dy, dx)))
    return min(directed, 180.0 - directed)


def facing_camera(world: np.ndarray) -> float:
    """How square the shoulders are to the lens, 0..1. Mirrors `Angles.facingCamera`.

    1 is fully front-on, 0 fully side-on, derived from how foreshortened the
    shoulder line is in depth versus width. Returns 0.0 rather than NaN on a
    degenerate shoulder line, matching the Kotlin's non-nullable contract - it
    fails closed, so a depth gate reading `facing > 0.9` is not tripped.
    """
    left, right = world[L_SHOULDER], world[R_SHOULDER]
    width, depth = abs(left[0] - right[0]), abs(left[2] - right[2])
    span = math.hypot(width, depth)
    if span < EPSILON:
        return 0.0
    return min(1.0, max(0.0, width / span))


def vertical_scale(world: np.ndarray, image: np.ndarray) -> float:
    """Metres of real vertical travel per unit of normalised image y.

    Mirrors `Angles.verticalScale`. The athlete's own torso is the ruler: known
    in metres from world space and in frame-fractions from image space, so their
    ratio converts one into the other and recalibrates every frame, staying
    correct even as the athlete drifts nearer or further from the lens.
    """
    torso_metres = distance(shoulder_centre(world), hip_centre(world))
    torso_image = distance_2d(shoulder_centre_image(image), hip_centre_image(image))
    if torso_metres < EPSILON or torso_image < EPSILON:
        return float("nan")
    return torso_metres / torso_image


def hip_rise_metres(world: np.ndarray, image: np.ndarray, baseline_image_y: float) -> float:
    """How far the hips have risen above a baseline, in metres. Mirrors `Angles.hipRiseMetres`.

    The jump signal for spiking and blocking. Image y increases downward, so
    rising hips means a decreasing y - hence the subtraction order rather than a
    unary minus.
    """
    scale = vertical_scale(world, image)
    if not np.isfinite(scale):
        return float("nan")
    return (baseline_image_y - float(hip_centre_image(image)[1])) * scale


# --- Ports of the analyzer helpers ---------------------------------------

def bilateral_mean(left: float, right: float) -> float:
    """Averages two sides, falling back to whichever one is measurable.

    Mirrors `SkillAnalyzer.bilateralMean`. Deliberately not `np.nanmean`: the
    values agree, but nanmean over two NaNs emits a RuntimeWarning per frame,
    and a clip's worth of those buries the warnings that matter.
    """
    left_ok, right_ok = np.isfinite(left), np.isfinite(right)
    if left_ok and right_ok:
        return (left + right) / 2.0
    if left_ok:
        return float(left)
    if right_ok:
        return float(right)
    return float("nan")


def hitting_side(image: np.ndarray) -> int:
    """Which arm is hitting, from whichever wrist is higher in frame.

    Mirrors `AnalyzerSupport.hittingSide`, including the tie going to Left.
    Image y increases downward, so the smaller y is the raised arm.
    """
    return LEFT if image[L_WRIST][1] <= image[R_WRIST][1] else RIGHT


def hands_above_nose_metres(world: np.ndarray) -> float:
    """Wrist midpoint height above the nose, in metres. Mirrors `SetAnalyzer`.

    Positive means the hands are above the face, which is the setting cue.
    Subtraction order carries the y-down compensation.
    """
    return float(world[NOSE][1] - midpoint(world[L_WRIST], world[R_WRIST])[1])


def penetration_metres(world: np.ndarray) -> float:
    """How far the hands reach past the shoulders, along the athlete's forward
    direction, in metres. Mirrors `BlockAnalyzer.penetrationMetres`.

    This used to read `shoulderCentre.z - wristMid.z` and suppress itself when
    `facing_camera` exceeded 0.9. Both halves were backwards: a raw z difference
    is anatomical penetration only when the athlete faces the lens, and the gate
    discarded exactly those frames. Correlation against the true quantity was
    +0.92 square to the camera and -0.26 most side-on - so the gate kept the
    anticorrelated regime and threw away the accurate one.
    """
    wrists = midpoint(world[L_WRIST], world[R_WRIST])
    return along_forward(world, shoulder_centre(world), wrists)


def wrist_rise_above_shoulders(image: np.ndarray) -> float:
    """Highest wrist above the shoulder line, in normalised image units."""
    return float(shoulder_centre_image(image)[1] - min(image[L_WRIST][1], image[R_WRIST][1]))


def mean_wrist_rise_above_shoulders(image: np.ndarray) -> float:
    """Wrist midpoint above the shoulder line, in normalised image units."""
    return float(shoulder_centre_image(image)[1]
                 - (image[L_WRIST][1] + image[R_WRIST][1]) / 2.0)


# --- The feature vector ---------------------------------------------------

# Order is fixed and load-bearing: it is the model's input column order, so
# appending is safe and reordering silently invalidates every model trained
# before the change. Each entry mirrors a measurement the Kotlin analyzers
# already make, named after its Kotlin counterpart wherever one exists.
FEATURE_NAMES = (
    "elbow_l", "elbow_r", "elbow_mean",
    "knee_l", "knee_r", "knee_mean",
    "shoulder_l", "shoulder_r",
    "hitting_side",
    "elbow_hitting", "shoulder_hitting", "shoulder_tossing",
    "trunk_forward_lean", "trunk_lateral_lean", "shoulder_tilt",
    "hands_above_nose_m", "hand_separation_m", "penetration_m",
    "facing_camera",
    "vertical_scale", "hip_centre_image_y", "hip_rise_m",
    "wrist_rise_above_shoulders", "mean_wrist_rise_above_shoulders",
)

# The unit each column is denominated in, so a summary can avoid pooling degrees
# with metres into one table and calling the result a comparison.
FEATURE_UNITS = {
    "hitting_side": "side",
    "hands_above_nose_m": "metres", "hand_separation_m": "metres",
    "penetration_m": "metres", "hip_rise_m": "metres",
    "facing_camera": "ratio", "vertical_scale": "metres_per_image_y",
    "hip_centre_image_y": "image", "wrist_rise_above_shoulders": "image",
    "mean_wrist_rise_above_shoulders": "image",
}
for _name in FEATURE_NAMES:
    FEATURE_UNITS.setdefault(_name, "degrees")


def compute(world: np.ndarray, image: np.ndarray,
            baseline_image_y: float | None = None) -> np.ndarray:
    """One frame's measurements, all in the app's coordinate space.

    There is no all-or-nothing validity gate here, deliberately. The old code
    nulled every feature at once when the athlete was small in frame; the Kotlin
    has no such switch - a distant athlete still has a measurable elbow, and
    whether the capture was good enough is `PoseQualityValidator`'s question, not
    this function's. Each measurement fails on its own terms or not at all.

    `baseline_image_y` is the athlete's standing hip height, which only a rep
    segmenter knows. Without it jump height is genuinely unknown, so it comes
    back NaN rather than as a plausible zero. Columns 20 and 21 are its two
    ingredients, so it can be filled in later without re-running MediaPipe.
    """
    if world is None or image is None or not np.isfinite(world).all():
        return np.full(len(FEATURE_NAMES), np.nan)

    elbow_l = angle_at(world[L_SHOULDER], world[L_ELBOW], world[L_WRIST])
    elbow_r = angle_at(world[R_SHOULDER], world[R_ELBOW], world[R_WRIST])
    knee_l = angle_at(world[L_HIP], world[L_KNEE], world[L_ANKLE])
    knee_r = angle_at(world[R_HIP], world[R_KNEE], world[R_ANKLE])
    shoulder_l = angle_at(world[L_HIP], world[L_SHOULDER], world[L_ELBOW])
    shoulder_r = angle_at(world[R_HIP], world[R_SHOULDER], world[R_ELBOW])

    # The app resolves this once, from the rep's contact frame. Extraction has no
    # reps, so this is the frame's own answer and the column is emitted so a
    # later segmenter can take the contact frame's value and redo the three
    # side-dependent columns correctly.
    side = hitting_side(image)

    return np.array([
        elbow_l, elbow_r, bilateral_mean(elbow_l, elbow_r),
        knee_l, knee_r, bilateral_mean(knee_l, knee_r),
        shoulder_l, shoulder_r,
        float(side),
        elbow_l if side == LEFT else elbow_r,
        shoulder_l if side == LEFT else shoulder_r,
        shoulder_r if side == LEFT else shoulder_l,
        trunk_forward_lean(world), trunk_lateral_lean(world), shoulder_tilt(world),
        hands_above_nose_metres(world),
        distance(world[L_WRIST], world[R_WRIST]),
        penetration_metres(world),
        facing_camera(world),
        vertical_scale(world, image),
        float(hip_centre_image(image)[1]),
        (hip_rise_metres(world, image, baseline_image_y)
         if baseline_image_y is not None else float("nan")),
        wrist_rise_above_shoulders(image),
        mean_wrist_rise_above_shoulders(image),
    ], dtype=np.float64)


# Columns that must never be averaged between frames. `hitting_side` is a label,
# not a quantity - halfway between the left arm and the right arm is not an arm.
# The three angle columns are *selected by* it, so they have to be sampled from
# the same source frame or one output row could pair a left-arm elbow with a
# right-arm shoulder: a body that never existed.
NEAREST_NEIGHBOUR_COLUMNS = frozenset({
    "hitting_side", "elbow_hitting", "shoulder_hitting", "shoulder_tossing",
})


def resample(sequence: np.ndarray, length: int,
             names: tuple[str, ...] | None = None) -> np.ndarray:
    """Stretches or squashes a sequence onto a fixed number of steps.

    Two reps of the same skill take different amounts of time, and the same rep
    shot at 60 fps has twice the rows of one shot at 30. A model fed raw rows
    would learn frame rate. Interpolating on the time axis removes both problems
    at once, and is why clips from a phone and clips pulled off the web can sit
    in the same training set.

    Continuous columns interpolate linearly over their own finite samples.
    `np.interp` does not skip NaN, so a single dropped-tracking frame would
    otherwise poison every output sample bracketing it - one bad frame becoming a
    bad span. A column with nothing finite in it stays entirely NaN, which is the
    honest answer.

    The columns in `NEAREST_NEIGHBOUR_COLUMNS` take the nearest source frame
    instead, and all of them take the *same* source frame, so a resampled row is
    always a real frame's worth of one arm rather than a blend of two. Blending
    them produced values like 0.032 and 0.161 in a column whose only meaningful
    values are 0 and 1 - 7% of samples in the sample corpus.

    `names` labels the columns; when omitted it defaults to FEATURE_NAMES if the
    width matches, and otherwise every column is treated as continuous, which is
    right for the landmark arrays this is also used on.
    """
    if sequence.ndim == 1:
        sequence = sequence.reshape(-1, 1)
    if len(sequence) == 0:
        return np.full((length, sequence.shape[1]), np.nan)
    if len(sequence) == 1:
        return np.repeat(sequence, length, axis=0)

    if names is None and sequence.shape[1] == len(FEATURE_NAMES):
        names = FEATURE_NAMES

    src = np.linspace(0.0, 1.0, len(sequence))
    dst = np.linspace(0.0, 1.0, length)
    # One shared index for the whole categorical group, so the arm they describe
    # stays consistent across the four columns.
    nearest = np.clip(np.rint(dst * (len(sequence) - 1)).astype(int), 0, len(sequence) - 1)

    out = np.empty((length, sequence.shape[1]), dtype=np.float64)
    for col in range(sequence.shape[1]):
        column = sequence[:, col]
        if names is not None and names[col] in NEAREST_NEIGHBOUR_COLUMNS:
            out[:, col] = column[nearest]
            continue
        finite = np.isfinite(column)
        if not finite.any():
            out[:, col] = np.nan
        else:
            out[:, col] = np.interp(dst, src[finite], column[finite])
    return out


# --- The jump baseline, recovered from the segment --------------------------

# `hip_rise_m` is a difference from a *standing* hip height. The app gets that
# height from `RepSegmenter`, which knows where a repetition begins; extraction
# has segments, not reps, so the baseline is estimated from the segment itself.
# This estimate assumes the athlete stands for most of the segment. The median
# resists brief crouches (larger image y) and jumps (smaller image y). A high
# percentile instead selects the crouch and overstates rise, even while standing.
# Continuous jumping/crouching without a standing majority needs a measured
# baseline; this segment estimate cannot establish one.

HIP_RISE_COLUMN = FEATURE_NAMES.index("hip_rise_m")
HIP_IMAGE_Y_COLUMN = FEATURE_NAMES.index("hip_centre_image_y")
VERTICAL_SCALE_COLUMN = FEATURE_NAMES.index("vertical_scale")


def standing_baseline_image_y(sequence: np.ndarray) -> float:
    """The hip image-y the athlete stands at, estimated from a segment.

    NaN when the column holds nothing finite, which is the honest answer rather
    than a plausible-looking zero: a segment the detector never saw has no
    baseline, and a fabricated one would turn every frame of it into a jump.
    """
    column = sequence[:, HIP_IMAGE_Y_COLUMN]
    finite = column[np.isfinite(column)]
    if finite.size == 0:
        return float("nan")
    return float(np.median(finite))


def fill_hip_rise(sequence: np.ndarray) -> np.ndarray:
    """Returns a copy with `hip_rise_m` filled from the segment's own baseline.

    Both ingredients the app uses are already in the vector - the hip image-y
    and the per-frame vertical scale - so this adds no measurement and needs no
    second pass through MediaPipe, which is what the open item meant by "both
    ingredients are emitted".

    Frames before the athlete ever rises come back at or below zero. That is
    correct, not missing: a crouch is negative rise, and the loading phase of
    every jump skill is scored from exactly that.
    """
    out = sequence.copy()
    baseline = standing_baseline_image_y(out)
    if not np.isfinite(baseline):
        return out
    hip_y = out[:, HIP_IMAGE_Y_COLUMN]
    scale = out[:, VERTICAL_SCALE_COLUMN]
    rise = (baseline - hip_y) * scale
    out[:, HIP_RISE_COLUMN] = np.where(np.isfinite(rise), rise, np.nan)
    return out


# --- Locating contact -------------------------------------------------------

# Skills whose contact sits at the top of a jump, so the hip-rise signal locates
# it. Passing and setting contact at hand height instead, where there is no jump
# to read and the raised hands are the only evidence available.
JUMP_SKILLS = frozenset({"serve", "spike", "block"})
WRIST_RISE_COLUMN = FEATURE_NAMES.index("mean_wrist_rise_above_shoulders")

# Frames either side of contact whose confidences are pooled. Landmark
# confidence is worst exactly at contact - arms extended toward the lens, motion
# blur - so a single-frame reading would report the detector's worst moment as
# if it were the rep's typical one.
CONTACT_WINDOW = 3


def contact_frame_index(sequence: np.ndarray, skill: str = "unknown") -> int:
    """Best guess at the ball-contact frame within a segment.

    A pose proxy, not the ball: nothing here tracks one. On a jump skill the
    apex of the hip rise is the closest thing to contact; elsewhere it is the
    highest the hands get. Ties and all-NaN columns resolve to frame 0, which is
    at least deterministic rather than dependent on sort order.

    This is deliberately not `argmax` on the raw column: a segment whose hip
    rise was never filled (all NaN) would otherwise report its first frame as
    contact and be believed.
    """
    signal = (sequence[:, HIP_RISE_COLUMN] if skill in JUMP_SKILLS
              else sequence[:, WRIST_RISE_COLUMN])
    finite = np.isfinite(signal)
    if not finite.any():
        return 0
    return int(np.argmax(np.where(finite, signal, -np.inf)))
