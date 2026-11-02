"""Leakage controls and measured classifier metrics, independent of TensorFlow."""
from __future__ import annotations

import math
import numpy as np


def probabilities(values: np.ndarray) -> np.ndarray:
    values = np.asarray(values, dtype=float)
    if values.ndim != 2 or values.shape[1] < 2 or not len(values):
        raise ValueError("Expected a nonempty samples-by-classes probability matrix")
    if not np.isfinite(values).all() or (values < 0).any() or (values > 1).any():
        raise ValueError("Probabilities must be finite and between zero and one")
    if not np.allclose(values.sum(axis=1), 1, atol=1e-5):
        raise ValueError("Class probabilities must sum to one")
    return values


def wilson(correct: int, total: int) -> list[float] | None:
    if total == 0:
        return None
    z = 1.959963984540054
    p = correct / total
    denominator = 1 + z * z / total
    centre = (p + z * z / (2 * total)) / denominator
    half = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total)) / denominator
    return [max(0, centre - half), min(1, centre + half)]


def grouped_accuracy_interval(actual, predicted, groups):
    groups = np.asarray(groups)
    if groups.shape != (len(actual),):
        raise ValueError("Groups and predictions must align")
    unique = np.unique(groups)
    if len(unique) < 3:
        return None
    sizes = np.array([(groups == group).sum() for group in unique])
    correct = np.array([((np.asarray(actual) == predicted) & (groups == group)).sum() for group in unique])
    draws = np.random.default_rng(1337).integers(0, len(unique), size=(1000, len(unique)))
    estimates = correct[draws].sum(axis=1) / sizes[draws].sum(axis=1)
    return np.percentile(estimates, [2.5, 97.5]).tolist()


def group_all_correct_interval(actual, predicted, groups):
    groups = np.asarray(groups)
    if groups.shape != (len(actual),):
        raise ValueError("Groups and predictions must align")
    unique = np.unique(groups)
    successes = sum(bool((np.asarray(actual)[groups == group] == np.asarray(predicted)[groups == group]).all())
                    for group in unique)
    return wilson(successes, len(unique))


def metrics(actual, predicted_probabilities, classes: list[str], accept_above: float = 0.0, groups=None) -> dict:
    p = probabilities(predicted_probabilities)
    y = np.asarray(actual, dtype=int)
    if y.shape != (len(p),) or p.shape[1] != len(classes) or len(set(classes)) != len(classes):
        raise ValueError("Labels, probabilities and class names must align")
    if (y < 0).any() or (y >= len(classes)).any() or not 0 <= accept_above <= 1:
        raise ValueError("Invalid class indices or acceptance threshold")
    prediction = p.argmax(axis=1)
    matrix = np.zeros((len(classes), len(classes)), dtype=int)
    np.add.at(matrix, (y, prediction), 1)
    correct = int(np.trace(matrix))
    by_class = {}
    f1s, recalls = [], []
    for index, name in enumerate(classes):
        tp = int(matrix[index, index])
        support = int(matrix[index].sum())
        predicted = int(matrix[:, index].sum())
        precision = tp / predicted if predicted else None
        recall = tp / support if support else None
        # A present class that is never predicted has F1 zero, not an undefined
        # value that can be dropped to inflate a macro average.
        f1 = 2 * tp / (support + predicted) if support + predicted else None
        by_class[name] = {"precision": precision, "recall": recall, "f1": f1, "support": support}
        if support:
            f1s.append(f1)
            recalls.append(recall)
    confidence = p.max(axis=1)
    ece = 0.0
    for bin_index in range(10):
        mask = (confidence >= bin_index / 10) & (
            confidence <= 1 if bin_index == 9 else confidence < (bin_index + 1) / 10)
        if mask.any():
            ece += float(mask.mean()) * abs(float(confidence[mask].mean()) - float((prediction[mask] == y[mask]).mean()))
    accepted = confidence >= accept_above
    accepted_count = int(accepted.sum())
    accepted_correct = int(((prediction == y) & accepted).sum())
    one_hot = np.eye(len(classes))[y]
    return {
        "samples": len(y), "classes": classes, "confusion_matrix": matrix.tolist(),
        "confusion_axes": "rows actual, columns predicted", "per_class": by_class,
        "accuracy": correct / len(y), "accuracy_wilson_95": wilson(correct, len(y)),
        "wilson_assumption": "Independent samples; repeated player/video segments can violate this assumption",
        "linked_group_bootstrap_accuracy_95": grouped_accuracy_interval(y, prediction, groups) if groups is not None else None,
        "macro_f1": float(np.mean(f1s)), "balanced_accuracy": float(np.mean(recalls)),
        "all_classes_observed": bool((matrix.sum(axis=1) > 0).all()),
        "negative_log_likelihood": float(-np.log(np.clip(p[np.arange(len(y)), y], 1e-12, 1)).mean()),
        "brier_multiclass": float(np.square(p - one_hot).sum(axis=1).mean()),
        "expected_calibration_error_10_bins": ece,
        "selective": {"accept_above": accept_above, "coverage": accepted_count / len(y),
            "accepted": accepted_count, "accuracy": accepted_correct / accepted_count if accepted_count else None,
            "risk": 1 - accepted_correct / accepted_count if accepted_count else None,
            "accuracy_wilson_95": wilson(accepted_correct, accepted_count),
            "group_all_correct_wilson_95": group_all_correct_interval(y[accepted], prediction[accepted],
                np.asarray(groups)[accepted]) if groups is not None and accepted_count else None,
            "linked_group_bootstrap_accuracy_95": grouped_accuracy_interval(y[accepted], prediction[accepted],
                np.asarray(groups)[accepted]) if groups is not None and accepted_count else None},
    }


def temperature_scale(p, temperature: float):
    p = probabilities(p)
    if not math.isfinite(temperature) or temperature <= 0:
        raise ValueError("Temperature must be positive and finite")
    logits = np.log(np.clip(p, 1e-12, 1)) / temperature
    logits -= logits.max(axis=1, keepdims=True)
    exponential = np.exp(logits)
    return exponential / exponential.sum(axis=1, keepdims=True)


def fit_calibration(actual, p, classes, minimum_per_class: int = 10, groups=None) -> dict:
    """Caller must supply a disjoint calibration set; never training/test data."""
    p = probabilities(p)
    y = np.asarray(actual, dtype=int)
    # Validate before indexing or class-support checks.
    metrics(y, p, classes)
    if minimum_per_class < 1:
        raise ValueError("Minimum class support must be positive")
    counts = np.bincount(y, minlength=len(classes))
    if (counts < minimum_per_class).any():
        return {"status": "insufficient_calibration_data", "temperature": None,
                "accept_above": None, "minimum_per_class": minimum_per_class,
                "policy": "abstain", "reason": "Calibration lacks required support in each class"}
    if groups is None or len(np.unique(groups)) < 3:
        return {"status": "insufficient_calibration_groups", "temperature": None,
                "accept_above": None, "policy": "abstain",
                "reason": "Calibration needs at least three independent linked player/source groups"}
    groups = np.asarray(groups)
    if groups.shape != y.shape:
        raise ValueError("Calibration groups and samples must align")
    candidates = np.geomspace(0.25, 8, 81)
    losses = [-np.log(np.clip(temperature_scale(p, t)[np.arange(len(y)), y], 1e-12, 1)).mean() for t in candidates]
    temperature = float(candidates[int(np.argmin(losses))])
    calibrated = temperature_scale(p, temperature)
    # Threshold is only selected on calibration data. A lower confidence bound
    # protects against selecting a perfect-looking threshold on a handful of reps.
    selected = None
    for threshold in np.linspace(0.5, 0.99, 50):
        mask = calibrated.max(axis=1) >= threshold
        if any(int(((y == index) & mask).sum()) < minimum_per_class for index in range(len(classes))):
            continue
        interval = wilson(int((calibrated.argmax(axis=1)[mask] == y[mask]).sum()), int(mask.sum()))
        clustered = grouped_accuracy_interval(y[mask], calibrated.argmax(axis=1)[mask], groups[mask])
        group_interval = group_all_correct_interval(y[mask], calibrated.argmax(axis=1)[mask], groups[mask])
        if interval and interval[0] >= 0.80 and clustered is not None and clustered[0] >= 0.80 and group_interval and group_interval[0] >= .80:
            selected = float(threshold)
            break
    return {"status": "fitted" if selected is not None else "no_supported_acceptance_threshold",
            "temperature": temperature, "accept_above": selected,
            "minimum_per_class": minimum_per_class,
            "policy": "selective" if selected is not None else "abstain",
            "validation_metrics": metrics(y, calibrated, classes, selected or 1.0, groups)}


def qualify_on_locked_test(policy: dict, test_metrics: dict) -> dict:
    """Predeclared acceptance gate; a failed test never retunes the threshold."""
    if policy.get("policy") != "selective":
        return {**policy, "locked_test_passed": False}
    selective = test_metrics["selective"]
    lower = selective.get("accuracy_wilson_95")
    clustered = selective.get("linked_group_bootstrap_accuracy_95")
    group_interval = selective.get("group_all_correct_wilson_95")
    passed = bool(test_metrics.get("all_classes_observed") and selective["coverage"] >= .5
                  and lower and lower[0] >= .8 and clustered and clustered[0] >= .8
                  and group_interval and group_interval[0] >= .8)
    return {**policy, "locked_test_passed": passed, "policy": "selective" if passed else "abstain",
            "reason": "Predeclared locked-test risk/coverage gate passed" if passed
                else "Locked test did not support the proposed confidence policy; do not retune on this test"}


def connected_groups(rows: list[dict]) -> np.ndarray:
    """Link samples sharing any source, known player, session or duplicate hash."""
    parent = list(range(len(rows)))

    def root(index):
        while parent[index] != index:
            parent[index] = parent[parent[index]]
            index = parent[index]
        return index

    owners = {}
    for index, row in enumerate(rows):
        if not row.get("source_group"):
            raise ValueError("Every sample needs its original source group")
        for field in ("source_group", "athlete_id", "session_id", "source_sha256", "sequence_sha256"):
            value = row.get(field)
            if not value or str(value).lower() in {"unknown", "unverified", "none"}:
                continue
            key = (field, value)
            if key in owners:
                parent[root(index)] = root(owners[key])
            else:
                owners[key] = index
    return np.array([f"linked-{root(index)}" for index in range(len(rows))])


def assert_disjoint(groups, *partitions) -> None:
    groups = np.asarray(groups)
    seen_indices, seen_groups = set(), set()
    for partition in partitions:
        indices = set(np.asarray(partition, dtype=int).tolist())
        if any(i < 0 or i >= len(groups) for i in indices):
            raise ValueError("Partition index outside dataset")
        selected_groups = set(groups[list(indices)].tolist())
        if indices & seen_indices or selected_groups & seen_groups:
            raise ValueError("Data leakage: linked player/source/session appears across partitions")
        seen_indices |= indices
        seen_groups |= selected_groups


def grouped_folds(y, groups, classes: int):
    """Leave whole linked groups out, reporting class-deficient folds explicitly."""
    y, groups = np.asarray(y), np.asarray(groups)
    if len(y) != len(groups):
        raise ValueError("Labels and groups must align")
    for held in np.unique(groups):
        test = np.flatnonzero(groups == held)
        train = np.flatnonzero(groups != held)
        assert_disjoint(groups, train, test)
        reason = None
        if not len(train) or set(y[train].tolist()) != set(range(classes)):
            reason = "Training partition lacks a label class"
        yield {"held_group": str(held), "train": train, "test": test, "reason": reason}
