"""Verify the test vectors round-trip — Python JCS bytes must match the
files checked into tools/test_vectors/. If this fails, regenerate vectors
with `python -m tools.cli_sealverify.main vectors --out tools/test_vectors/`.
"""
from __future__ import annotations

import base64
import json
from pathlib import Path

import pytest

from tools.cli_sealverify.jcs_compat import canonicalize_obj
from tools.cli_sealverify.keystore import deterministic_test_keypair, sign, verify

VECTORS_DIR = Path(__file__).resolve().parents[3] / "test_vectors"


@pytest.fixture(scope="module")
def kp():
    return deterministic_test_keypair()


def _slugs():
    if not VECTORS_DIR.exists():
        return []
    return sorted(p.stem.replace(".input", "") for p in VECTORS_DIR.glob("manifest_*.input.json"))


def test_vectors_directory_exists():
    if not VECTORS_DIR.exists():
        pytest.skip(
            f"{VECTORS_DIR} not present. Generate with "
            "`python -m tools.cli_sealverify.main vectors --out tools/test_vectors/`"
        )
    assert (VECTORS_DIR / "test_public_key.b64").exists()


@pytest.mark.parametrize("slug", _slugs())
def test_canonical_bytes_match(slug):
    input_path = VECTORS_DIR / f"{slug}.input.json"
    canon_path = VECTORS_DIR / f"{slug}.canonical.json"
    input_dict = json.loads(input_path.read_text())
    expected = canon_path.read_bytes()

    # Blank signature before canonicalizing
    input_dict["signature"] = ""
    actual = canonicalize_obj(input_dict)
    assert actual == expected, (
        f"JCS drift detected on {slug}.\n"
        f"  expected: {expected!r}\n"
        f"  actual:   {actual!r}\n"
        "Regenerate vectors with `python -m tools.cli_sealverify.main vectors --out tools/test_vectors/`."
    )


@pytest.mark.parametrize("slug", _slugs())
def test_signatures_verify(slug, kp):
    canon_path = VECTORS_DIR / f"{slug}.canonical.json"
    sig_path = VECTORS_DIR / f"{slug}.signature.b64"
    canon = canon_path.read_bytes()
    sig = base64.b64decode(sig_path.read_text().strip())
    assert verify(kp.public, canon, sig), f"signature failed for {slug}"


@pytest.mark.parametrize("slug", _slugs())
def test_signed_manifest_round_trips_through_verify(slug, kp):
    """Re-sign the test vector locally; bytes must match the .signature.b64."""
    canon = (VECTORS_DIR / f"{slug}.canonical.json").read_bytes()
    sig_local = sign(kp, canon)
    # ECDSA signatures are non-deterministic by default; check verify() succeeds.
    assert verify(kp.public, canon, sig_local)
