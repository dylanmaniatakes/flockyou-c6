# Prebuilt C6 firmware package

These four binaries are committed so users can install the firmware through
`python3 flash.py` without installing PlatformIO or a compiler. They must all
come from the same successful build and must never be mixed with ESP32-S3
artifacts.

Maintainers can refresh the package from a verified local build with:

Run these commands from the repository root:

```bash
pio run
python3 scripts/package_firmware.py
```

The script copies the C6 `bootloader.bin`, `partitions.bin`, `boot_app0.bin`, and application as `flockyou-c6.bin` into this directory. Review the build and physical boot test before committing refreshed binaries.
