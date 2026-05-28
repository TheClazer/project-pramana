# Pramāṇa test vectors

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
