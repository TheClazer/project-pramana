"""ECDSA-P256 sign/verify — Python side.

The dev-laptop CLI generates its own keypair in a local file (NOT the
Android Keystore). For test vectors with deterministic signatures we
derive a fixed key from a known seed.

The Android side uses `Keystore.sign(bytes, Tier.TEE_ECDSA_P256)` which
produces a DER-encoded ECDSA-SHA256 signature. Both implementations write
and verify DER, so a signature produced on Android verifies in Python
(and vice versa) as long as the public key is in the trust store.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.ec import (
    ECDSA,
    EllipticCurvePrivateKey,
    EllipticCurvePublicKey,
)
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    PrivateFormat,
    PublicFormat,
)


@dataclass
class KeyPair:
    private: EllipticCurvePrivateKey
    public:  EllipticCurvePublicKey

    def public_der(self) -> bytes:
        return self.public.public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)

    def public_pem(self) -> bytes:
        return self.public.public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo)


def generate_keypair() -> KeyPair:
    sk = ec.generate_private_key(ec.SECP256R1())
    return KeyPair(private=sk, public=sk.public_key())


def deterministic_test_keypair(seed_int: int = 0xC0DE_F00D) -> KeyPair:
    """A fixed key derived from a seed integer — for test vectors ONLY.
    DO NOT use anywhere a real signature matters. The seed is checked into
    the repo on purpose so the vectors are reproducible by anyone.
    """
    # Build a deterministic scalar in [1, n-1] for the SECP256R1 curve.
    n = ec.SECP256R1().key_size                                # 256
    secret = (seed_int % ((1 << n) - 1)) or 1
    sk = ec.derive_private_key(secret, ec.SECP256R1())
    return KeyPair(private=sk, public=sk.public_key())


def sign(kp: KeyPair, payload: bytes) -> bytes:
    """ECDSA-SHA256 over `payload`. Returns DER bytes — same shape as Android."""
    return kp.private.sign(payload, ECDSA(hashes.SHA256()))


def verify(public: EllipticCurvePublicKey, payload: bytes, signature: bytes) -> bool:
    try:
        public.verify(signature, payload, ECDSA(hashes.SHA256()))
        return True
    except Exception:
        return False


def save_private_pem(kp: KeyPair, path: Path) -> None:
    path.write_bytes(
        kp.private.private_bytes(
            encoding=Encoding.PEM,
            format=PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )


def load_private_pem(path: Path) -> KeyPair:
    sk = serialization.load_pem_private_key(path.read_bytes(), password=None)
    return KeyPair(private=sk, public=sk.public_key())


def public_b64(pk: EllipticCurvePublicKey) -> str:
    der = pk.public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(der).decode("ascii")
