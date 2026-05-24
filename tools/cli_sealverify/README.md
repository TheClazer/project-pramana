# tools/cli_sealverify — Python CLI for seal/verify

Cross-language mirror of the Android Sealer + Verifier. Lets you:

1. **Round-trip locally** without the phone (JCS + ECDSA + EXIF + DCT all in Python).
2. **Generate test vectors** that pin the Kotlin and Python JCS implementations
   together — drift between them silently breaks every Pramāṇa signature.
3. **Simulate WhatsApp** by stripping EXIF and verifying the DCT watermark
   alone still surfaces a Pramāṇa origin claim.

## Setup

```powershell
cd D:\Work\project-pramana
pip install -r tools/cli_sealverify/requirements.txt
```

## Use

```powershell
# Generate a key (one-time per dev laptop)
python -m tools.cli_sealverify.main keygen --out devkey.pem

# Seal an image
python -m tools.cli_sealverify.main seal `
    --in some_photo.jpg --out sealed.jpg --key devkey.pem

# Verify it
python -m tools.cli_sealverify.main verify --in sealed.jpg --key devkey.pem
# → state: verified_original

# Full round-trip + stripped-EXIF case (WhatsApp simulation)
python -m tools.cli_sealverify.main roundtrip --in some_photo.jpg --key devkey.pem
```

## Test vectors (for CI + Android cross-validation)

```powershell
python -m tools.cli_sealverify.main vectors --out tools/test_vectors/
```

Generates 3 manifests with:
  - `{slug}.input.json` — pre-canonical, signature blanked
  - `{slug}.canonical.json` — JCS canonical bytes (UTF-8)
  - `{slug}.signature.b64` — ECDSA-SHA256 with the deterministic test key
  - `{slug}.signed.json` — final signed manifest

Both Android JUnit (`JcsTest`) and the Python pytest suite consume these
vectors. If they don't agree byte-for-byte, the canonicalizer is wrong.

## Bible refs

- §7  — manifest schema + JCS rule
- §10 — RFC 8785 JCS published library
- §13 — DO NOT hand-roll canonicalization
