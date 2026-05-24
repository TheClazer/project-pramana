"""Pramāṇa CLI — seal and verify on the dev laptop.

Usage:
    # Generate a fresh key + seal an image
    python -m tools.cli_sealverify.main keygen --out devkey.pem
    python -m tools.cli_sealverify.main seal --in photo.jpg --out sealed.jpg --key devkey.pem
    python -m tools.cli_sealverify.main verify --in sealed.jpg --key devkey.pem

    # Round-trip including stripped-EXIF simulation
    python -m tools.cli_sealverify.main roundtrip --in photo.jpg --key devkey.pem

    # Generate the test-vector files (used by Android JcsTest + CI)
    python -m tools.cli_sealverify.main vectors --out ../test_vectors/
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .keystore import (
    KeyPair,
    deterministic_test_keypair,
    generate_keypair,
    load_private_pem,
    public_b64,
    save_private_pem,
)
from .seal import seal_image
from .verify import verify_image


def _load_or_create_key(path: Path) -> KeyPair:
    if path.exists():
        return load_private_pem(path)
    kp = generate_keypair()
    save_private_pem(kp, path)
    print(f"Generated new keypair at {path}")
    return kp


def _trust_dict(kp: KeyPair) -> dict[str, str]:
    import hashlib
    der = kp.public_der()
    key_id_hex = hashlib.sha256(der).hexdigest()
    return {key_id_hex: public_b64(kp.public)}


def _cmd_keygen(args):
    kp = generate_keypair()
    save_private_pem(kp, args.out)
    print(f"Wrote {args.out}")
    print(f"Public (base64): {public_b64(kp.public)}")


def _cmd_seal(args):
    kp = _load_or_create_key(args.key)
    sealed = seal_image(args.in_path, args.out, kp)
    print("Sealed.")
    print(f"  out: {args.out}")
    print(f"  keyId: {sealed.manifest.key_id}")
    print(f"  contentHash: {sealed.manifest.content_hash}")
    print(f"  signature: {sealed.manifest.signature[:32]}…")


def _cmd_verify(args):
    kp = load_private_pem(args.key)
    result = verify_image(args.in_path, _trust_dict(kp))
    print(f"state: {result.state}")
    if result.reason:
        print(f"reason: {result.reason}")
    if result.manifest:
        print(f"manifest.keyId:       {result.manifest.key_id}")
        print(f"manifest.contentHash: {result.manifest.content_hash}")
        print(f"manifest.detection:   {result.manifest.detection}")
    print(f"watermark_found: {result.watermark_found}")
    sys.exit(0 if result.state == "verified_original" else 1)


def _cmd_roundtrip(args):
    kp = _load_or_create_key(args.key)
    sealed_path = args.in_path.with_name("sealed_" + args.in_path.name)
    sealed = seal_image(args.in_path, sealed_path, kp)
    print(f"Sealed → {sealed_path}")

    trust = _trust_dict(kp)

    print("\n=== Verify sealed file ===")
    r1 = verify_image(sealed_path, trust)
    print(f"state: {r1.state}  reason: {r1.reason}")

    # Strip EXIF and re-verify (WhatsApp simulation)
    from .exif_io import strip_exif
    stripped = args.in_path.with_name("stripped_" + args.in_path.name)
    strip_exif(sealed_path, stripped)
    print(f"\n=== Verify EXIF-stripped file (WhatsApp simulation) ===")
    r2 = verify_image(stripped, trust)
    print(f"state: {r2.state}  reason: {r2.reason}  watermark_found: {r2.watermark_found}")


def _cmd_vectors(args):
    """Generate test vector files for cross-language JCS validation."""
    from .jcs_compat import canonicalize_obj
    from .manifest import DetectionMeta, Manifest, SensorMeta
    out_dir = args.out
    out_dir.mkdir(parents=True, exist_ok=True)

    kp = deterministic_test_keypair()
    pub_b64 = public_b64(kp.public)
    import hashlib
    key_id = hashlib.sha256(kp.public_der()).hexdigest()

    vectors = [
        Manifest(
            captured_at="1718368472103",
            device_fingerprint="ab" * 32,
            sensor=SensorMeta(model="iQOO Z5", iso="100", exposure_us="8333", focal_length_mm="4.38"),
            content_hash="cd" * 32,
            detection=DetectionMeta(score="0.04", label="GENUINE",
                                    model="pramana-mobilenet-v3-small-int8-v1", backend="NPU"),
            key_id=key_id,
        ),
        Manifest(
            captured_at="1700000000000",
            device_fingerprint="11" * 32,
            sensor=SensorMeta(model="Snapdragon-DevBoard", iso="200", exposure_us="16666", focal_length_mm="5.0"),
            content_hash="ee" * 32,
            detection=DetectionMeta(score="0.92", label="FAKE",
                                    model="pramana-efficientnet-b0-int8-v1", backend="GPU"),
            key_id=key_id,
        ),
        Manifest(
            captured_at="1735689600000",
            device_fingerprint="00" * 32,
            sensor=SensorMeta(model="Galaxy S24", iso="50", exposure_us="4000", focal_length_mm="6.7"),
            content_hash="aa" * 32,
            detection=DetectionMeta(score="0.55", label="SUSPICIOUS",
                                    model="pramana-mobilenet-v3-small-int4-v1", backend="CPU"),
            key_id=key_id,
        ),
    ]
    from .keystore import sign
    import base64
    out_dir.joinpath("README.md").write_text(_README_VECTORS)
    out_dir.joinpath("test_public_key.b64").write_text(pub_b64 + "\n")

    for i, m in enumerate(vectors, start=1):
        canonical = canonicalize_obj(m.to_json_dict_for_signing())
        sig = sign(kp, canonical)
        sig_b64 = base64.b64encode(sig).decode("ascii")
        signed = Manifest(**{**m.__dict__, "signature": sig_b64})

        slug = f"manifest_{i:03d}"
        out_dir.joinpath(f"{slug}.input.json").write_text(json.dumps(m.to_json_dict(), indent=2))
        out_dir.joinpath(f"{slug}.canonical.json").write_bytes(canonical)
        out_dir.joinpath(f"{slug}.signed.json").write_text(json.dumps(signed.to_json_dict(), indent=2))
        out_dir.joinpath(f"{slug}.signature.b64").write_text(sig_b64 + "\n")
        print(f"  wrote {slug}.* (canonical {len(canonical)} B, sig {len(sig)} B)")
    print(f"\nWrote {len(vectors)} test vectors to {out_dir}")
    print(f"Test public key written to {out_dir/'test_public_key.b64'}")


_README_VECTORS = """# Pramāṇa test vectors

These files pin the cross-language contract between:
  - Kotlin Sealer/Verifier (android/...)
  - Python CLI (tools/cli_sealverify/)

Schema: 3 manifests, each with:
  - {slug}.input.json       — pre-canonical, signature blanked
  - {slug}.canonical.json   — JCS canonical bytes (UTF-8)
  - {slug}.signature.b64    — ECDSA-SHA256 with the deterministic test key
  - {slug}.signed.json      — final signed manifest

`test_public_key.b64` — the deterministic ECDSA-P256 public key
(SubjectPublicKeyInfo DER, base64). Anyone can verify the signatures.

If the Kotlin canonicalizer or Python canonicalizer drifts, regenerate
the vectors and any new bytes that don't match — that's a real bug.

Regenerate:
  python -m tools.cli_sealverify.main vectors --out tools/test_vectors/
"""


def _build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="pramana-cli")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("keygen"); p.add_argument("--out", type=Path, required=True); p.set_defaults(func=_cmd_keygen)

    p = sub.add_parser("seal")
    p.add_argument("--in", dest="in_path", type=Path, required=True)
    p.add_argument("--out", type=Path, required=True)
    p.add_argument("--key", type=Path, required=True)
    p.set_defaults(func=_cmd_seal)

    p = sub.add_parser("verify")
    p.add_argument("--in", dest="in_path", type=Path, required=True)
    p.add_argument("--key", type=Path, required=True)
    p.set_defaults(func=_cmd_verify)

    p = sub.add_parser("roundtrip")
    p.add_argument("--in", dest="in_path", type=Path, required=True)
    p.add_argument("--key", type=Path, required=True)
    p.set_defaults(func=_cmd_roundtrip)

    p = sub.add_parser("vectors")
    p.add_argument("--out", type=Path, required=True)
    p.set_defaults(func=_cmd_vectors)
    return ap


def main():
    args = _build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
