"""Pramāṇa CLI seal / verify — Python mirror of the Android pipeline.

Purpose:
    * Round-trip seal + verify on the dev laptop without the Android app.
    * Cross-language test vectors — if the Kotlin and Python JCS bytes
      disagree, every signature breaks silently. The CLI is the canary.
    * Stripped-EXIF case validation — simulate WhatsApp by removing EXIF
      and confirming the DCT watermark still surfaces a fingerprint.
"""
__version__ = "0.1.0"
