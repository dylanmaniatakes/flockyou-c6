#!/usr/bin/env python3
"""Collect a successful XIAO ESP32-C6 PlatformIO build for flash.py.

This script copies files; it never rebuilds them. Keeping packaging separate
from compilation makes it obvious which artifacts came from the last verified
`pio run`. The resulting package is committed for the original-style Python
flasher, so maintainers must boot-test it before publishing.
"""

from pathlib import Path
import os
import shutil
import sys


PROJECT_ROOT = Path(__file__).resolve().parent.parent
BUILD_DIR = PROJECT_ROOT / ".pio" / "build" / "seeed_xiao_esp32c6"
OUTPUT_DIR = PROJECT_ROOT / "firmware"


def platformio_core_dir() -> Path:
    """Return PlatformIO's data directory, honoring its documented override."""
    configured = os.environ.get("PLATFORMIO_CORE_DIR")
    return Path(configured).expanduser() if configured else Path.home() / ".platformio"


def required_source(path: Path, description: str) -> Path:
    if not path.is_file():
        raise FileNotFoundError(
            f"Missing {description}: {path}\nRun `pio run` successfully first."
        )
    return path


def main() -> int:
    framework_dir = (
        platformio_core_dir()
        / "packages"
        / "framework-arduinoespressif32"
        / "tools"
        / "partitions"
    )

    files = {
        "bootloader.bin": required_source(BUILD_DIR / "bootloader.bin", "C6 bootloader"),
        "partitions.bin": required_source(BUILD_DIR / "partitions.bin", "partition table"),
        "boot_app0.bin": required_source(framework_dir / "boot_app0.bin", "OTA data image"),
        "flockyou-c6.bin": required_source(BUILD_DIR / "firmware.bin", "application image"),
    }

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for output_name, source in files.items():
        destination = OUTPUT_DIR / output_name
        shutil.copy2(source, destination)
        print(f"{source} -> {destination}")

    print("\nC6 firmware package ready for: python3 flash.py")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except FileNotFoundError as error:
        print(error, file=sys.stderr)
        raise SystemExit(1)
