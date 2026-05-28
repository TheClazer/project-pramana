"""EXIF UserComment read/write — same prefix convention as Kotlin `Exif.kt`.

The Kotlin side writes:    "PMNA1:" + base64(manifest_jcs_utf8_bytes)
into TAG_USER_COMMENT (0x9286). The Python side reads/writes the same.
"""
from __future__ import annotations

import base64
from pathlib import Path

import piexif
import piexif.helper

PREFIX = "PMNA1:"


def embed_user_comment(jpeg_path: Path, manifest_bytes: bytes) -> None:
    """Write `manifest_bytes` (JCS UTF-8) into the JPEG's EXIF UserComment."""
    exif_dict = piexif.load(str(jpeg_path))
    b64 = base64.b64encode(manifest_bytes).decode("ascii")
    # piexif stores UserComment with a leading 8-byte type prefix per the EXIF spec.
    # piexif.helper.UserComment handles that for us.
    user_comment = piexif.helper.UserComment.dump(PREFIX + b64, encoding="ascii")
    exif_dict["Exif"][piexif.ExifIFD.UserComment] = user_comment
    exif_bytes = piexif.dump(exif_dict)
    piexif.insert(exif_bytes, str(jpeg_path))


def read_user_comment(jpeg_path: Path) -> bytes | None:
    try:
        exif_dict = piexif.load(str(jpeg_path))
    except Exception:
        return None
    raw = exif_dict.get("Exif", {}).get(piexif.ExifIFD.UserComment)
    if not raw:
        return None
    try:
        text = piexif.helper.UserComment.load(raw)
    except Exception:
        return None
    if not text.startswith(PREFIX):
        return None
    return base64.b64decode(text[len(PREFIX):])


def strip_exif(jpeg_path: Path, out_path: Path) -> None:
    """Simulate WhatsApp / Instagram stripping — re-save the image with no EXIF."""
    from PIL import Image
    img = Image.open(jpeg_path)
    img.save(out_path, format="JPEG", quality=90)  # no exif kwarg → no EXIF written
