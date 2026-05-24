"""RFC 8785 JSON Canonicalization — Python mirror of the Kotlin Erdtman impl.

Bible §7 + §13 anti-pattern: do NOT hand-roll. We use the published `jcs`
PyPI package which is a clean-room reference implementation that has been
tested against the same vectors as Erdtman's Java library.

The Kotlin side uses `io.github.erdtman:java-json-canonicalization:1.1`.
The two MUST produce byte-identical output. The test vectors in
`tools/test_vectors/` are what we use to confirm this on every CI run.
"""
from __future__ import annotations

import json
from typing import Any

import jcs


def canonicalize_obj(obj: Any) -> bytes:
    """JCS-canonicalize any JSON-serializable Python object."""
    # jcs.canonicalize takes a Python object (dict/list/...) and returns bytes.
    return jcs.canonicalize(obj)


def canonicalize_json_text(text: str) -> bytes:
    """JCS-canonicalize a JSON string."""
    return jcs.canonicalize(json.loads(text))
