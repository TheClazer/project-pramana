"""POS algorithm (Wang et al. 2017) — Python reference for the Kotlin port.

Bible §6: "POS algorithm projects the RGB signal onto a skin-tone-invariant
plane, isolating the pulsatile component."

The Kotlin `rppg/PosAlgorithm.kt` MUST agree with this implementation on any
input (modulo float precision). Test vectors in `tests/test_pos.py` pin the
behavior.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


def pos_project(red: np.ndarray, green: np.ndarray, blue: np.ndarray) -> np.ndarray:
    """Project the RGB time series (each shape [N]) onto the POS axis.
    Returns a 1D signal of length N.
    """
    assert red.shape == green.shape == blue.shape, "RGB lengths must match"

    mean_r = max(float(red.mean()), 1e-6)
    mean_g = max(float(green.mean()), 1e-6)
    mean_b = max(float(blue.mean()), 1e-6)

    rn = red / mean_r - 1.0
    gn = green / mean_g - 1.0
    bn = blue / mean_b - 1.0

    # Two orthogonal projection axes (POS paper, Eq. 5)
    x = gn - bn
    y = -2.0 * rn + gn + bn

    s_x = float(x.std())
    s_y = float(y.std())
    alpha = s_x / s_y if s_y > 1e-6 else 1.0
    return (x + alpha * y).astype(np.float32)


@dataclass
class HeartBandSnr:
    snr: float
    peak_hz: float
    bpm: float


def heart_band_snr(
    projected: np.ndarray,
    fps: float = 30.0,
    low_hz: float = 0.75,
    high_hz: float = 3.0,
) -> HeartBandSnr:
    """Power in [low_hz, high_hz] / total power. Higher = stronger pulse signal."""
    detrended = projected - projected.mean()
    n = len(detrended)
    # rFFT for real signal
    spectrum = np.fft.rfft(detrended)
    power = (spectrum.real ** 2 + spectrum.imag ** 2)
    freqs = np.fft.rfftfreq(n, d=1.0 / fps)

    in_band_mask = (freqs >= low_hz) & (freqs <= high_hz)
    in_band_power = float(power[in_band_mask].sum())
    total_power = float(power.sum()) + 1e-12
    snr = in_band_power / total_power

    if in_band_power > 0:
        in_band_idx = np.where(in_band_mask)[0]
        peak_idx = in_band_idx[np.argmax(power[in_band_mask])]
        peak_hz = float(freqs[peak_idx])
    else:
        peak_hz = 0.0
    return HeartBandSnr(snr=float(np.clip(snr, 0.0, 1.0)), peak_hz=peak_hz, bpm=peak_hz * 60.0)
