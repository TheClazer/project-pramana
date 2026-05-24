"""Verify the POS algorithm against synthetic inputs. These vectors also
serve as the cross-language pin: the Kotlin port `PosAlgorithm.kt` should
agree with these expected outputs."""
from __future__ import annotations

import numpy as np
import pytest

from src.rppg.pos import heart_band_snr, pos_project


def test_flat_signals_project_to_near_zero():
    n = 30
    r = np.full(n, 150.0, dtype=np.float32)
    g = np.full(n, 100.0, dtype=np.float32)
    b = np.full(n, 80.0, dtype=np.float32)
    out = pos_project(r, g, b)
    assert np.allclose(out, 0.0, atol=1e-5)


def test_synthetic_pulse_yields_high_snr_in_band():
    fs, n, hz = 30.0, 90, 1.2
    t = np.arange(n) / fs
    base_g = 100.0 + 3.0 * np.sin(2 * np.pi * hz * t)
    r = np.full(n, 150.0, dtype=np.float32)
    g = base_g.astype(np.float32)
    b = np.full(n, 80.0, dtype=np.float32)
    projected = pos_project(r, g, b)
    res = heart_band_snr(projected, fps=fs)
    assert res.snr > 0.5
    assert 0.75 <= res.peak_hz <= 3.0


def test_white_noise_yields_low_snr():
    rng = np.random.default_rng(42)
    n = 90
    r = (150.0 + rng.standard_normal(n)).astype(np.float32)
    g = (100.0 + rng.standard_normal(n)).astype(np.float32)
    b = (80.0 + rng.standard_normal(n)).astype(np.float32)
    projected = pos_project(r, g, b)
    res = heart_band_snr(projected, fps=30.0)
    # White noise distributes power roughly uniformly; the heart band is
    # 2.25 Hz wide out of 15 Hz Nyquist ≈ 15%. SNR should be in that
    # neighborhood, well below the 0.5 "real pulse" threshold.
    assert res.snr < 0.5


def test_mismatched_lengths_raise():
    r = np.zeros(30, dtype=np.float32)
    g = np.zeros(29, dtype=np.float32)
    b = np.zeros(30, dtype=np.float32)
    with pytest.raises(AssertionError):
        pos_project(r, g, b)


def test_snr_is_in_unit_interval():
    rng = np.random.default_rng(0)
    for _ in range(20):
        sig = rng.standard_normal(120).astype(np.float32)
        res = heart_band_snr(sig, fps=30.0)
        assert 0.0 <= res.snr <= 1.0
