"""Asserts the Python geometry agrees with the Android app's, vector for vector.

    python test_parity.py

Every case here is transcribed from `app/src/test/.../AnglesTest.kt` and
`TestPoses.kt`, using the same inputs and the same expected outputs. If this
passes, `features.py` and `Angles.kt` compute the same numbers; if it fails, the
two halves of the project have drifted and any threshold derived offline is
wrong on the phone.

That drift is not hypothetical. The pipeline originally measured angles in image
space, where a perspective projection distorts a joint by an amount depending on
where the athlete stands. Measured against world space on real footage the two
disagreed by 23 degrees on average, with 70% of measurements off by more than 10
and elbows off by as much as 128. Both produced plausible-looking degrees, which
is exactly why this file exists: plausible is not the same as correct, and only a
shared test vector can tell them apart.

Kept as a plain script rather than a pytest suite so it runs with no extra
dependency, the same way `label_clips.py` does.
"""
from __future__ import annotations

import sys

import numpy as np

import features

TOLERANCE = 0.5  # degrees, matching AnglesTest.TOLERANCE

failures: list[str] = []


def check(name: str, actual, expected, tolerance: float = TOLERANCE) -> None:
    if expected is None:
        ok = actual is None or (isinstance(actual, float) and np.isnan(actual))
        detail = f"expected None, got {actual}"
    else:
        ok = actual is not None and np.isfinite(actual) and abs(actual - expected) <= tolerance
        detail = f"expected {expected}, got {actual}"
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + ("" if ok else f"   ({detail})"))
    if not ok:
        failures.append(name)


def standing() -> tuple[np.ndarray, np.ndarray]:
    """TestPoses.wellFramedStanding(), as (world, image) arrays of 33 landmarks.

    Untouched landmarks sit at the origin exactly as the Kotlin fixture leaves
    them, because several assertions depend on a degenerate triple being
    unmeasurable rather than zero.
    """
    world = np.zeros((features.NUM_LANDMARKS, 3))
    image = np.zeros((features.NUM_LANDMARKS, 2))

    for point, (x, y, z) in {
        features.NOSE: (0.0, -0.62, -0.06),
        features.L_SHOULDER: (-0.18, -0.45, 0.0),
        features.R_SHOULDER: (0.18, -0.45, 0.0),
        features.L_ELBOW: (-0.20, -0.17, 0.0),
        features.R_ELBOW: (0.20, -0.17, 0.0),
        features.L_WRIST: (-0.22, 0.09, 0.0),
        features.R_WRIST: (0.22, 0.09, 0.0),
        features.L_HIP: (-0.09, 0.0, 0.0),
        features.R_HIP: (0.09, 0.0, 0.0),
        features.L_KNEE: (-0.10, 0.45, 0.0),
        features.R_KNEE: (0.10, 0.45, 0.0),
        features.L_ANKLE: (-0.10, 0.88, 0.0),
        features.R_ANKLE: (0.10, 0.88, 0.0),
    }.items():
        world[point] = (x, y, z)

    for point, (x, y) in {
        features.NOSE: (0.50, 0.16),
        features.L_SHOULDER: (0.44, 0.24),
        features.R_SHOULDER: (0.56, 0.24),
        features.L_ELBOW: (0.42, 0.36),
        features.R_ELBOW: (0.58, 0.36),
        features.L_WRIST: (0.41, 0.47),
        features.R_WRIST: (0.59, 0.47),
        features.L_HIP: (0.46, 0.50),
        features.R_HIP: (0.54, 0.50),
        features.L_KNEE: (0.46, 0.65),
        features.R_KNEE: (0.54, 0.65),
        features.L_ANKLE: (0.46, 0.80),
        features.R_ANKLE: (0.54, 0.80),
    }.items():
        image[point] = (x, y)

    return world, image


def main() -> int:
    print("Parity with app/src/test/.../AnglesTest.kt\n")

    print("Angles.at - interior angle in world space")
    # `straight limb measures 180 degrees`: shoulder above, elbow at the joint,
    # wrist below. World y increases downward, so the shoulder is the negative one.
    check("straight limb is 180",
          features.angle_at(np.array([0, -1., 0]), np.array([0, 0., 0]), np.array([0, 1., 0])), 180)
    # `perpendicular segments measure 90 degrees`
    check("perpendicular is 90",
          features.angle_at(np.array([1., 0, 0]), np.array([0., 0, 0]), np.array([0, 1., 0])), 90)
    # `coincident landmarks give null rather than zero` - the contract that stops a
    # tracking dropout from being scored as bad form.
    check("coincident is unmeasurable",
          features.angle_at(np.array([0., 0, 0]), np.array([0., 0, 0]), np.array([0, 1., 0])), None)

    # Not transcribed from AnglesTest - added because every case there lies in a
    # plane, so an implementation that silently dropped z passed all ten of them.
    # `angle_at` feeds 11 of the 24 columns, and measuring a projection instead of
    # the real angle is the exact defect this whole module exists to prevent.
    # These two arms are 90 degrees apart in space and identical once flattened.
    check("depth is not discarded",
          features.angle_at(np.array([1., 0, 1]), np.array([0., 0, 0]), np.array([1., 0, -1])), 90)

    world, image = standing()

    print("\nAngles.trunkForwardLean - sagittal, against the athlete's own facing")
    check("upright trunk is 0", features.trunk_forward_lean(world), 0)
    # `leaning forward is positive forward lean`. The nose travels with the torso -
    # leaving it behind describes a pose no body can hold, and used to pass only
    # because the measurement never asked which way the athlete faced.
    leaning = world.copy()
    leaning[features.L_SHOULDER] = (-0.18, -0.45, -0.45)
    leaning[features.R_SHOULDER] = (0.18, -0.45, -0.45)
    leaning[features.NOSE] = (0.0, -0.62, -0.51)
    check("leaning forward is +45", features.trunk_forward_lean(leaning), 45)

    # `leaning forward reads positive with the athlete facing away too` - the bug
    # this fix exists for. Turned around, the same lean used to read -45.
    away = world.copy()
    away[features.L_SHOULDER] = (0.18, -0.45, 0.45)
    away[features.R_SHOULDER] = (-0.18, -0.45, 0.45)
    away[features.NOSE] = (0.0, -0.62, 0.51)
    check("same lean facing away is also +45", features.trunk_forward_lean(away), 45)

    # `forward lean is unmeasurable when the nose cannot resolve facing`
    faceless = world.copy()
    faceless[features.NOSE] = (0.0, -0.62, 0.0)
    check("no usable face is unmeasurable", features.trunk_forward_lean(faceless), None)

    print("\nAngles.shoulderTilt")
    check("level shoulders are ~0", features.shoulder_tilt(world), 0, tolerance=1.0)
    # Mirrors AnglesTest.`level shoulders read zero from the front too`. The shared
    # Kotlin fixture lays the shoulders out rear-view, so on its own it exercises
    # only one of the two directions the camera-frame x axis can produce - which is
    # exactly how a 180-degree error survived every test on both sides.
    front_on = world.copy()
    front_on[features.L_SHOULDER] = (0.18, -0.45, 0.0)
    front_on[features.R_SHOULDER] = (-0.18, -0.45, 0.0)
    check("level shoulders are ~0 from the front too",
          features.shoulder_tilt(front_on), 0, tolerance=1.0)

    print("\nAngles.facingCamera")
    check("square to camera scores 1", features.facing_camera(world), 1.0)

    print("\nAngles.verticalScale - world metres per unit of image y")
    # Torso is 0.45 m in world space and 0.26 of frame height in image space.
    check("scale is torso_metres / torso_image",
          features.vertical_scale(world, image), 0.45 / 0.26, tolerance=0.05)

    print("\nAngles.hipRiseMetres")
    scale = features.vertical_scale(world, image)
    hip_image_y = (image[features.L_HIP][1] + image[features.R_HIP][1]) / 2
    check("baseline a tenth below gives 0.1 * scale",
          features.hip_rise_metres(world, image, hip_image_y + 0.1), 0.1 * scale)
    check("hips at baseline give no rise",
          features.hip_rise_metres(world, image, hip_image_y), 0.0)

    print("\nInternal consistency")
    # Nothing else would catch a name added without a value, or the reverse: the
    # arrays would just be off by one and every column after the gap would carry
    # the wrong label, silently, all the way into the model.
    check("FEATURE_NAMES matches compute() width",
          float(len(features.compute(world, image))), float(len(features.FEATURE_NAMES)))

    # hitting_side is a label; a resampled 0.16 would be neither arm.
    sides = np.zeros((10, len(features.FEATURE_NAMES)))
    sides[5:, features.FEATURE_NAMES.index("hitting_side")] = 1.0
    resampled = features.resample(sides, 32)[:, features.FEATURE_NAMES.index("hitting_side")]
    check("resample keeps hitting_side categorical",
          float(np.isin(resampled, [0.0, 1.0]).all()), 1.0)

    print()
    if failures:
        print(f"{len(failures)} FAILED: {', '.join(failures)}")
        return 1
    print("all parity checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
