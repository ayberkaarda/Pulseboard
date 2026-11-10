"""HMAC-SHA256 signing of internal request bodies."""

import hashlib
import hmac
import re

SIGNATURE_HEADER = "X-Internal-Signature"
_HEX_SHA256 = re.compile(r"[0-9a-f]{64}")


def sign(secret: str, body: bytes) -> str:
    """Return the lowercase hex HMAC-SHA256 of ``body``."""
    return hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()


def verify(secret: str, body: bytes, signature: str | None) -> bool:
    """Constant-time check of ``signature`` against the HMAC of ``body``."""
    if signature is None or _HEX_SHA256.fullmatch(signature) is None:
        return False
    return hmac.compare_digest(sign(secret, body), signature)
