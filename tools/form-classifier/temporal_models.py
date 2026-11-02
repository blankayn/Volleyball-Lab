"""Small temporal models with order-sensitive motion features and native exports."""
from __future__ import annotations

import numpy as np

ARCHITECTURES = ("linear", "tcn", "lstm", "gru")


def build_model(architecture: str, steps: int, features: int, feature_names=None):
    from tensorflow import keras
    L = keras.layers
    inputs = keras.Input(shape=(steps, features))
    if architecture == "linear":
        x = L.GlobalAveragePooling1D()(inputs)
    else:
        # Differences are per normalized sequence step, not physical m/s.
        # Derived inside the graph so exported inference cannot forget them.
        differences = inputs[:, 1:, :] - inputs[:, :-1, :]
        if feature_names and "observed_frame" in feature_names:
            mask_index = feature_names.index("observed_frame")
            pair_observed = inputs[:, 1:, mask_index:mask_index + 1] * inputs[:, :-1, mask_index:mask_index + 1]
            differences = differences * pair_observed
        deltas = L.ZeroPadding1D((1, 0))(differences)
        x = L.Concatenate()([inputs, deltas])
        if architecture == "tcn":
            for dilation in (1, 2, 4):
                x = L.Conv1D(8, 3, padding="causal", dilation_rate=dilation, activation="relu",
                             kernel_regularizer=keras.regularizers.l2(1e-3))(x)
            x = L.Concatenate()([L.GlobalAveragePooling1D()(x), L.GlobalMaxPooling1D()(x)])
        elif architecture == "lstm":
            x = L.LSTM(8, unroll=True, kernel_regularizer=keras.regularizers.l2(1e-3))(x)
        elif architecture == "gru":
            x = L.GRU(8, unroll=True, kernel_regularizer=keras.regularizers.l2(1e-3))(x)
        else:
            raise ValueError(f"Unknown architecture {architecture}")
        x = L.Dropout(0.2)(x)
    outputs = L.Dense(2, activation="softmax", kernel_regularizer=keras.regularizers.l2(1e-3))(x)
    return keras.Model(inputs, outputs, name=f"coach_{architecture}")


def augment_training(X: np.ndarray, feature_names: list[str], copies: int, seed: int) -> np.ndarray:
    """Training-only mild shared time warp and normalized-feature jitter.

    Categorical side identity and its selected arm columns share nearest-frame
    sampling. Explicit observation-mask recipes sample the entire frame with
    nearest neighbors and omit jitter, keeping missing values and masks aligned.
    """
    if copies < 0 or copies > 10:
        raise ValueError("Augmentation copies must be between zero and ten")
    rng = np.random.default_rng(seed)
    categorical = {"hitting_side", "elbow_hitting", "shoulder_hitting", "shoulder_tossing", "observed_frame"}
    outputs = [X.copy()]
    variable = np.std(X, axis=(0, 1)) > 1e-8
    source = np.linspace(0, 1, X.shape[1])
    masked_recipe = "observed_frame" in feature_names
    for _ in range(copies):
        augmented = np.empty_like(X)
        for row, sequence in enumerate(X):
            intervals = rng.uniform(.9, 1.1, X.shape[1] - 1)
            positions = np.r_[0, np.cumsum(intervals)]
            positions /= positions[-1]
            nearest = np.abs(positions[:, None] - source[None, :]).argmin(axis=1)
            for col, name in enumerate(feature_names):
                if masked_recipe or name in categorical:
                    augmented[row, :, col] = sequence[nearest, col]
                else:
                    jitter = rng.normal(0, .015, X.shape[1]) if variable[col] else 0
                    augmented[row, :, col] = np.interp(positions, source, sequence[:, col]) + jitter
        outputs.append(augmented)
    return np.concatenate(outputs)
