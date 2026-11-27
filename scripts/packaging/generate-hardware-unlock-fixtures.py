#!/usr/bin/env python3
"""Synthetic PRF-envelope vectors. Test-only dependency: Python cryptography. No real secrets."""
import base64
import hashlib
import hmac
import json
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def generate():
    vectors = []
    for id_length, key_length in [(1, 16), (64, 32), (1024, 24)]:
        credential = bytes(index % 256 for index in range(id_length))
        salt, prf, nonce = bytes(range(32)), bytes(range(32, 64)), bytes(range(64, 76))
        key = bytes(range(128, 128 + key_length))
        header = b"OTP\x01" + id_length.to_bytes(2, "big") + credential + salt + nonce
        wrapping = hmac.new(prf, b"OfflineOTP hardware unlock key v1", hashlib.sha256).digest()
        envelope = header + AESGCM(wrapping).encrypt(nonce, key, header)
        raw = dict(credentialId=credential, salt=salt, prf=prf, nonce=nonce, vaultKey=key, envelope=envelope)
        vectors.append({name: base64.b64encode(value).decode("ascii") for name, value in raw.items()})
    return dict(description="Deterministic synthetic format vectors; not real credentials. "
                "Python cryptography AESGCM + HMAC-SHA256. Consumed by JVM and Swift contract tests; "
                "execution status is recorded separately.", vectors=vectors)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    target = root / "desktop/src/test/resources/golden/hardware-unlock-envelope.json"
    target.write_text(json.dumps(generate(), indent=2) + "\n", encoding="utf-8")
