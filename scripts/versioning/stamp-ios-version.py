#!/usr/bin/env python3
"""Stamp the built iOS Info.plist before signing, including local Xcode builds."""
import datetime as dt
import os
import plistlib
from pathlib import Path

AKST = dt.timezone(dt.timedelta(hours=-9))


def versions(epoch=None):
    instant = dt.datetime.now(dt.timezone.utc) if epoch is None else dt.datetime.fromtimestamp(int(epoch), dt.timezone.utc)
    local = instant.astimezone(AKST)
    day = (local.date() - dt.date(2020, 1, 1)).days + 1
    if not 1 <= day <= 9999:
        raise ValueError("Build timestamp is outside the supported native package version range")
    return {
        "OfflineOTPBuildVersion": local.strftime("V%Y-%m-%d-%H-%M"),
        "CFBundleShortVersionString": f"{local.year}.{local.month}.{local.day}",
        "CFBundleVersion": f"{day}.{local.hour}.{local.minute}",
    }


def stamp(path, epoch=None):
    with path.open("rb") as source:
        info = plistlib.load(source)
    info.update(versions(epoch))
    with path.open("wb") as output:
        plistlib.dump(info, output, fmt=plistlib.FMT_BINARY)


if __name__ == "__main__":
    stamp(Path(os.environ["TARGET_BUILD_DIR"]) / os.environ["INFOPLIST_PATH"],
          os.environ.get("SOURCE_DATE_EPOCH"))
