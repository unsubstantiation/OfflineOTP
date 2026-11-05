#!/usr/bin/env python3
"""Generate the fixed token vectors the Swift client is tested against.

The point of this file is independence: the Swift TOTP/HOTP/Steam/mOTP code has to reproduce
numbers that were produced by something other than itself. This script is that something, and it
checks itself against the published RFC 4226 (HOTP) and RFC 6238 (TOTP) test vectors before it
writes anything, so a wrong truncation or a swapped hash cannot quietly become a "known" vector.

The secrets are the ones in the shared golden vault (`desktop/src/test/resources/golden/`), so the
Swift tests prove two things at once: the OTP maths is right, and the seed a vault entry stores is
the seed that is actually used.

Usage:
    scripts/ios/generate-token-vectors.py            # verify the checked-in file matches
    scripts/ios/generate-token-vectors.py --write    # regenerate after an intentional change
"""
import argparse
import base64
import hashlib
import hmac
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "ios/Tests/OfflineOTPKitTests/Fixtures/token-vectors.json"
GOLDEN_VAULT = ROOT / "desktop/src/test/resources/golden/expected-vault.json"

STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"
MOTP_STEP_SECONDS = 10


def hotp(secret: bytes, counter: int, digits: int, algorithm: str) -> str:
    """RFC 4226 section 5.3 dynamic truncation."""
    digest = hmac.new(secret, counter.to_bytes(8, "big"), getattr(hashlib, algorithm)).digest()
    offset = digest[-1] & 0x0F
    truncated = (
        ((digest[offset] & 0x7F) << 24)
        | ((digest[offset + 1] & 0xFF) << 16)
        | ((digest[offset + 2] & 0xFF) << 8)
        | (digest[offset + 3] & 0xFF)
    )
    return str(truncated % (10**digits)).zfill(digits)


def totp(secret: bytes, unix_time: int, digits: int, period: int, algorithm: str) -> str:
    return hotp(secret, unix_time // period, digits, algorithm)


def steam(secret: bytes, unix_time: int, digits: int, period: int, algorithm: str) -> str:
    """Steam Guard: the raw (untruncated) TOTP value, mapped onto the 26-character alphabet."""
    digest = hmac.new(secret, (unix_time // period).to_bytes(8, "big"),
                      getattr(hashlib, algorithm)).digest()
    offset = digest[-1] & 0x0F
    value = (
        ((digest[offset] & 0x7F) << 24)
        | ((digest[offset + 1] & 0xFF) << 16)
        | ((digest[offset + 2] & 0xFF) << 8)
        | (digest[offset + 3] & 0xFF)
    )
    out = ""
    for _ in range(digits):
        out += STEAM_ALPHABET[value % len(STEAM_ALPHABET)]
        value //= len(STEAM_ALPHABET)
    return out


def motp(secret: str, pin: str, unix_time: int, offset: int = 0) -> str:
    """Legacy mOTP: first six hex digits of MD5(step + secret + PIN)."""
    step = (unix_time // MOTP_STEP_SECONDS) + offset
    return hashlib.md5(f"{step}{secret}{pin}".encode()).hexdigest()[:6]


def decode_base32(text: str) -> bytes:
    return base64.b32decode(text.upper() + "=" * (-len(text) % 8))


def verify_against_standards() -> None:
    """Refuse to emit vectors unless this implementation reproduces the published ones."""
    rfc4226_secret = b"12345678901234567890"
    rfc4226 = ["755224", "287082", "359152", "969429", "338314",
               "254676", "287922", "162583", "399871", "520489"]
    for counter, expected in enumerate(rfc4226):
        actual = hotp(rfc4226_secret, counter, 6, "sha1")
        assert actual == expected, f"RFC 4226 counter {counter}: {actual} != {expected}"

    secrets = {
        "sha1": b"12345678901234567890",
        "sha256": b"12345678901234567890123456789012",
        "sha512": (b"12345678901234567890123456789012"
                   b"34567890123456789012345678901234"),
    }
    rfc6238 = {
        "sha1": {59: "94287082", 1111111109: "07081804", 1111111111: "14050471",
                 1234567890: "89005924", 2000000000: "69279037", 20000000000: "65353130"},
        "sha256": {59: "46119246", 1111111109: "68084774", 1111111111: "67062674",
                   1234567890: "91819424", 2000000000: "90698825", 20000000000: "77737706"},
        "sha512": {59: "90693936", 1111111109: "25091201", 1111111111: "99943326",
                   1234567890: "93441116", 2000000000: "38618901", 20000000000: "47863826"},
    }
    for algorithm, vectors in rfc6238.items():
        for unix_time, expected in vectors.items():
            actual = totp(secrets[algorithm], unix_time, 8, 30, algorithm)
            assert actual == expected, f"RFC 6238 {algorithm} t={unix_time}: {actual} != {expected}"


def golden_entries() -> list:
    return json.loads(GOLDEN_VAULT.read_text(encoding="utf-8"))


def build() -> dict:
    entries = golden_entries()
    totp_entry = next(entry for entry in entries if entry["type"] == "TOTP")
    hotp_entry = next(entry for entry in entries if entry["type"] == "HOTP")

    totp_secret = decode_base32(totp_entry["secret"])
    hotp_secret = decode_base32(hotp_entry["secret"])
    times = [1700000000, 1700000030, 1700000059, 0, 20000000000]

    return {
        "comment": "Generated by scripts/ios/generate-token-vectors.py from the shared golden vault; "
                   "do not edit by hand.",
        "steamAlphabet": STEAM_ALPHABET,
        "motpStepSeconds": MOTP_STEP_SECONDS,
        "totp": {
            "secretBase32": totp_entry["secret"],
            "sharesGoldenVaultEntry": True,
            "digits": totp_entry["digits"],
            "period": totp_entry["period"],
            "algorithm": totp_entry["algorithm"],
            "vectors": [{"unixTime": t,
                         "code": totp(totp_secret, t, totp_entry["digits"],
                                      totp_entry["period"], totp_entry["algorithm"].lower()),
                         "steam": steam(totp_secret, t, 5,
                                        totp_entry["period"],
                                        totp_entry["algorithm"].lower())}
                        for t in times],
        },
        "hotp": {
            "secretBase32": hotp_entry["secret"],
            "sharesGoldenVaultEntry": True,
            "digits": hotp_entry["digits"],
            "algorithm": hotp_entry["algorithm"],
            "vectors": [{"counter": c,
                         "code": hotp(hotp_secret, c, hotp_entry["digits"],
                                      hotp_entry["algorithm"].lower())}
                        for c in (0, 1, 8, 9, 10, 2**32, 2**53)],
        },
        "motp": {
            "secret": "4c6f6e67206c697665207468652070696e21",
            "pin": "1234",
            "vectors": [{"unixTime": t, "offset": offset, "code": motp("4c6f6e67206c697665207468652070696e21",
                                                                       "1234", t, offset)}
                        for t, offset in ((1700000000, 0), (1700000009, 0), (1700000010, 0),
                                          (1700000000, 1), (0, 0))],
        },
    }


def render(document: dict) -> str:
    return json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def main(argv: list) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true",
                        help="replace the checked-in fixture instead of verifying it")
    args = parser.parse_args(argv)

    verify_against_standards()
    text = render(build())

    if args.write:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(text, encoding="utf-8")
        print(f"wrote {OUTPUT.relative_to(ROOT)}")
        return 0

    if not OUTPUT.is_file():
        print(f"missing {OUTPUT.relative_to(ROOT)}; run with --write", file=sys.stderr)
        return 1
    if OUTPUT.read_text(encoding="utf-8") != text:
        print(f"{OUTPUT.relative_to(ROOT)} is stale; run with --write", file=sys.stderr)
        return 1
    print("token vectors match the generator and the RFC 4226/6238 vectors")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
