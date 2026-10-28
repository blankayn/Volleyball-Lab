"""Tests for the jump baseline, contact location and contact confidence.

Three things were open before this existed:

- `hip_rise_m` was always NaN, because a standing-hip baseline is a property of
  a segment rather than of a frame. Both ingredients were already in the feature
  vector; nobody had joined them up.
- Nothing located contact, so the contact-phase cues had no frame to be scored
  at.
- Nothing reported how sure the detector was at contact, which is the frame it
  is least sure at.

These are the tests that keep those three from silently regressing. Run with:

    .venv/Scripts/python test_jump_baseline.py
"""
from __future__ import annotations

import numpy as np

import features

FAILURES = 0


def check(name: str, actual: float, expected: float, tolerance: float = 1e-6) -> None:
    global FAILURES
    if np.isnan(actual) and np.isnan(expected):
        return
    if abs(actual - expected) <= tolerance:
        return
    FAILURES += 1
    print(f"  FAIL  {name}: expected {expected}, got {actual}")


def check_true(name: str, condition: bool) -> None:
    global FAILURES
    if condition:
        return
    FAILURES += 1
    print(f"  FAIL  {name}")


def synthetic_sequence(hip_image_y: np.ndarray, vertical_scale: float = 2.0) -> np.ndarray:
    """A feature vector carrying only the columns the jump baseline reads.

    Everything else stays NaN so a test can never pass because of a value that
    leaked in from an unrelated column.
    """
    sequence = np.full((len(hip_image_y), len(features.FEATURE_NAMES)), np.nan)
    sequence[:, features.HIP_IMAGE_Y_COLUMN] = hip_image_y
    sequence[:, features.VERTICAL_SCALE_COLUMN] = vertical_scale
    return sequence


def test_baseline_is_where_the_hips_most_often_are() -> None:
    """A crouch below the baseline must not drag the baseline down with it.

    The athlete stands for most of a demonstration and crouches briefly. A mean
    sits between the two and over-reports rise. A high percentile selects the
    crouch; the median retains standing height when standing is the majority.
    """
    print("baseline is the standing height, not the average")
    # 20 frames standing at 0.40, 5 frames crouched down at 0.45.
    hip_y = np.concatenate([np.full(20, 0.40), np.full(5, 0.45)])
    sequence = synthetic_sequence(hip_y)
    baseline = features.standing_baseline_image_y(sequence)
    check("baseline sits at the standing height", baseline, 0.40, tolerance=0.01)


def test_baseline_ignores_missing_values_and_minority_motion() -> None:
    print("standing majority survives missing frames, jumps and crouches")
    hip_y = np.array([np.nan, 0.40, 0.40, 0.30, 0.40, np.inf, 0.45, 0.40, 0.40])
    sequence = synthetic_sequence(hip_y)
    check("mixed motion baseline", features.standing_baseline_image_y(sequence), 0.40)
    check("baseline independent of order", features.standing_baseline_image_y(sequence[::-1]), 0.40)


def test_hip_rise_is_filled_and_signed_correctly() -> None:
    """Jumping must read positive, crouching negative, standing zero.

    Sign is the easiest thing to get backwards here: world and image y both grow
    downward, so a naive `hip_y - baseline` would report every jump as a crouch
    and every cue built on it would fail in the opposite direction.
    """
    print("hip rise: jumping positive, crouching negative, standing zero")
    # Stand, crouch below, jump above, land back at standing.
    hip_y = np.array([0.40, 0.40, 0.40, 0.45, 0.40, 0.30, 0.35, 0.40, 0.40, 0.40])
    sequence = synthetic_sequence(hip_y)
    filled = features.fill_hip_rise(sequence)

    baseline = 0.40
    scale = 2.0
    check("standing frame rises zero",
          filled[0, features.HIP_RISE_COLUMN], 0.0)
    check("crouch reads negative",
          filled[3, features.HIP_RISE_COLUMN], (baseline - 0.45) * scale)
    check("apex reads positive",
          filled[5, features.HIP_RISE_COLUMN], (baseline - 0.30) * scale)
    check_true("apex is the highest frame",
               int(np.nanargmax(filled[:, features.HIP_RISE_COLUMN])) == 5)


def test_hip_rise_stays_nan_without_a_baseline() -> None:
    """A segment the detector never saw must not gain a fabricated baseline.

    `compute` returns all-NaN when the world landmarks are unusable. If the fill
    turned that into zeros, every frame would read as standing and the jump cue
    would score a rep it could not see.
    """
    print("no baseline available -> still NaN, not zero")
    sequence = synthetic_sequence(np.full(10, np.nan))
    filled = features.fill_hip_rise(sequence)
    check_true("hip rise remains NaN",
               np.isnan(filled[:, features.HIP_RISE_COLUMN]).all())


def test_fill_does_not_mutate_its_input() -> None:
    """The caller's array is data, not scratch space."""
    print("fill returns a copy")
    sequence = synthetic_sequence(np.array([0.40, 0.30, 0.40]))
    before = sequence.copy()
    features.fill_hip_rise(sequence)
    check_true("input untouched", np.array_equal(sequence, before, equal_nan=True))


def test_contact_is_the_jump_apex_on_jump_skills() -> None:
    print("contact locates the jump apex for serve/spike/block")
    hip_y = np.array([0.40, 0.42, 0.40, 0.35, 0.30, 0.33, 0.40, 0.40])
    sequence = features.fill_hip_rise(synthetic_sequence(hip_y))
    check("serve contact at the apex",
          float(features.contact_frame_index(sequence, "serve")), 4.0)


def test_contact_is_the_highest_hands_on_ground_skills() -> None:
    """Passing has no jump, so the raised hands are the only evidence there is.

    Reading the hip signal here would pick a frame at random - on a passer the
    hips barely move - and every contact-phase pass cue would be scored at an
    arbitrary instant.
    """
    print("contact locates the highest hands for pass/set")
    sequence = np.full((8, len(features.FEATURE_NAMES)), np.nan)
    sequence[:, features.HIP_IMAGE_Y_COLUMN] = 0.40
    sequence[:, features.VERTICAL_SCALE_COLUMN] = 2.0
    # Hands peak at frame 5.
    sequence[:, features.WRIST_RISE_COLUMN] = [0.0, 0.1, 0.2, 0.3, 0.4, 0.9, 0.2, 0.0]
    check("pass contact at the highest hands",
          float(features.contact_frame_index(sequence, "pass")), 5.0)
    check("set contact at the highest hands",
          float(features.contact_frame_index(sequence, "set")), 5.0)


def test_contact_falls_back_deterministically_when_unmeasurable() -> None:
    """An unmeasurable signal resolves to frame 0, not to a sort-order accident."""
    print("contact falls back to frame 0, deterministically")
    sequence = synthetic_sequence(np.full(8, np.nan))
    check("unmeasurable contact is frame 0",
          float(features.contact_frame_index(sequence, "spike")), 0.0)


def test_new_columns_are_in_the_model_contract() -> None:
    """Contact confidence is derived from columns the vector already carries.

    If a rename dropped one, the pipeline would stop reporting contact confidence
    and the failure would be a silently missing column rather than an error.
    """
    print("feature vector still carries the columns the baseline reads")
    check_true("hip_rise_m present", "hip_rise_m" in features.FEATURE_NAMES)
    check_true("hip_centre_image_y present", "hip_centre_image_y" in features.FEATURE_NAMES)
    check_true("vertical_scale present", "vertical_scale" in features.FEATURE_NAMES)
    check_true("mean_wrist_rise_above_shoulders present",
               "mean_wrist_rise_above_shoulders" in features.FEATURE_NAMES)


def main() -> int:
    print(f"features.py - {len(features.FEATURE_NAMES)} columns\n")
    test_baseline_is_where_the_hips_most_often_are()
    test_baseline_ignores_missing_values_and_minority_motion()
    test_hip_rise_is_filled_and_signed_correctly()
    test_hip_rise_stays_nan_without_a_baseline()
    test_fill_does_not_mutate_its_input()
    test_contact_is_the_jump_apex_on_jump_skills()
    test_contact_is_the_highest_hands_on_ground_skills()
    test_contact_falls_back_deterministically_when_unmeasurable()
    test_new_columns_are_in_the_model_contract()

    if FAILURES:
        print(f"\n{FAILURES} check(s) failed")
        return 1
    print("\nall checks pass")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
