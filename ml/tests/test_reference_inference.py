"""Pin the reference-inference contract. The Kotlin `PreprocessIntoTensor`
must produce numerically equivalent floats for the same input image."""
from __future__ import annotations

import numpy as np
from PIL import Image

from src.reference_inference import preprocess, softmax_fake_score


def test_preprocess_shape_and_dtype():
    img = Image.new("RGB", (640, 480), color=(128, 64, 200))
    arr = preprocess(img)
    assert arr.shape == (1, 3, 224, 224)
    assert arr.dtype == np.float32


def test_preprocess_pixel_normalization():
    img = Image.new("RGB", (224, 224), color=(255, 0, 128))
    arr = preprocess(img)
    # Red channel == 1.0, green == 0.0, blue == 128/255 ≈ 0.502
    assert abs(arr[0, 0, 100, 100] - 1.0) < 1e-5
    assert abs(arr[0, 1, 100, 100] - 0.0) < 1e-5
    assert abs(arr[0, 2, 100, 100] - 128 / 255.0) < 1e-3


def test_center_square_crop_keeps_center():
    # Non-square input — center pixel of output should be center pixel of input
    img = Image.new("RGB", (480, 240), color=(0, 0, 0))
    # Paint a red pixel in the exact center
    px = img.load()
    px[240, 120] = (255, 0, 0)
    arr = preprocess(img)
    # After center-square crop (240x240 from x=120..360, y=0..240) and resize to 224,
    # the originally-center pixel maps to roughly (112, 112) in output.
    assert arr[0, 0, 112, 112] > 0.5  # red channel non-trivial


def test_softmax_fake_score_basic():
    logits = np.array([[2.0, 1.0]])   # real wins
    assert softmax_fake_score(logits) < 0.5
    logits = np.array([[1.0, 3.0]])   # fake wins
    assert softmax_fake_score(logits) > 0.7
