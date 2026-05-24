"""Python mirror of `io.teamsnapped.pramana.api.Manifest`.

Field names + ordering MUST match the Kotlin @Serializable schema with the
exact `@SerialName` values (snake_case for the on-wire form).
"""
from __future__ import annotations

from dataclasses import asdict, dataclass


@dataclass
class SensorMeta:
    model: str
    iso: str
    exposure_us: str
    focal_length_mm: str


@dataclass
class DetectionMeta:
    score: str
    label: str
    model: str
    backend: str


@dataclass
class Manifest:
    captured_at: str
    device_fingerprint: str
    sensor: SensorMeta
    content_hash: str
    detection: DetectionMeta
    key_id: str
    signature: str = ""
    version: str = "1.0"
    generator: str = "Pramana/1.0"

    def to_json_dict(self) -> dict:
        d = asdict(self)
        return d

    def to_json_dict_for_signing(self) -> dict:
        """Strip the signature field for canonicalization-then-sign."""
        d = self.to_json_dict()
        d["signature"] = ""
        return d

    @classmethod
    def from_dict(cls, d: dict) -> "Manifest":
        return cls(
            version=d.get("version", "1.0"),
            generator=d.get("generator", "Pramana/1.0"),
            captured_at=d["captured_at"],
            device_fingerprint=d["device_fingerprint"],
            sensor=SensorMeta(**d["sensor"]),
            content_hash=d["content_hash"],
            detection=DetectionMeta(**d["detection"]),
            key_id=d["key_id"],
            signature=d.get("signature", ""),
        )
