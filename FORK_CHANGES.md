# Fork change log

This is the running, human-readable record of differences between `dylanmaniatakes/flockyou-c6` and `colonelpanichacks/oui-spy-unified-blue`. Add an entry for every logical change, including documentation and build-system changes. Do not rewrite older entries when new work happens; append a new dated section.

## Baseline

- Upstream repository: `colonelpanichacks/oui-spy-unified-blue`
- Fork repository: `dylanmaniatakes/flockyou-c6`
- Starting upstream branch: `master`
- Starting commit: `f20204305c3ee7bfa5cb0bb7a06e445f2a8bfbe6`
- Baseline date: July 25, 2026

## 2026-07-25 — Initial FlockYou-only ESP32-C6 port

### Repository and scope

- Connected the local checkout's `origin` remote to the fork and added the original project as `upstream`.
- Removed the unified boot selector and its NVS mode-selection behavior.
- Removed Detector, Foxhunter, and Sky Spy sources.
- Removed copied raw sources, old BLE compatibility headers, drone/OpenDroneID files, and the multi-repository sync script.
- Promoted the upstream FlockYou implementation to the sole `src/main.cpp` application.

### Removed S3-only artifacts and hardware

- Removed the committed ESP32-S3 bootloader, partition, OTA, and application binaries. They cannot run on the RISC-V ESP32-C6.
- Removed NeoPixel initialization, animation state, alert flashes, and the Adafruit NeoPixel dependency because the target hardware has no NeoPixel.
- Removed L76K/TinyGPSPlus UART initialization, parsing, state, status fields, and dependency because the target hardware has no GPS module.
- Retained optional browser/phone geolocation because it requires no ESP32 peripheral or wiring.

### XIAO ESP32-C6 port

- Changed the PlatformIO board from `seeed_xiao_esp32s3` to `seeed_xiao_esp32c6`.
- Pinned pioarduino release `54.03.21-2`, which supplies Arduino-ESP32 3.2.1 on ESP-IDF 5.4.2 for the C6.
- Replaced S3 PSRAM, 240 MHz, 8 MB flash, and QIO/OPI memory assumptions with the C6 board defaults: 160 MHz, no PSRAM, and 4 MB flash.
- Mapped the buzzer by physical XIAO label `D2`. D2 resolves to GPIO2 on C6; the original S3 raw GPIO3 value also happened to mean D2 on that board.
- Migrated NimBLE-Arduino from the 1.x scan API to 2.5.0: const scan results, `NimBLEScanCallbacks`, `setScanCallbacks()`, and millisecond scan durations.
- Disabled unused NimBLE broadcaster, peripheral, and central roles.
- Moved the maintained async web-server dependency to `ESP32Async/ESPAsyncWebServer` 3.11.2 and pinned ArduinoJson 7.4.2.

### Flash and partitions

- Replaced the invalid 8 MB partition table with a 4 MB single-app table: almost 3 MB application space and 1 MB SPIFFS.
- Changed the standalone flasher target to `esp32c6`, 4 MB DIO at 80 MHz, and added Seeed native USB VID `2886`.
- Kept the verified C6 image offsets: bootloader `0x0000`, partition table `0x8000`, OTA data `0xe000`, and app `0x10000`.
- Added a packaging helper to collect C6 PlatformIO outputs for the standalone flasher without committing generated binaries.

### Documentation

- Rewrote the README for the single-purpose C6 firmware.
- Added a prominent maintainer notice identifying Dylan Maniatakes, the July 25, 2026 fork date, the use of AI-assisted vibecoding, and the need for independent code review.
- Added detailed hardware/pin guidance and an architecture/modification guide.
- Created this append-only difference log.
- Updated the plain-text flashing guide for the C6 and FlockYou access point.

### Verification

- `pio run`: successful.
- Target reported by PlatformIO: ESP32-C6, 160 MHz, 320 KB RAM, 4 MB flash.
- RAM: 74,044 bytes used (22.6%).
- App partition: 1,306,979 bytes used (42.4% of 3,080,192 bytes).
- `python -m py_compile flash.py scripts/package_firmware.py`: successful.
- Standalone `flash.py` write to an ESP32-C6 revision 0.2: successful; all four images passed hash verification.
- The first physical flash exposed a QIO/DIO mismatch in `flash.py`: forcing QIO rewrote the DIO bootloader header and produced an early watchdog-reset loop. Changing the standalone flasher to DIO fixed the boot path.
- Runtime detection behavior still requires field testing with known advertisements.

### Compatibility investigation retained for future maintainers

The first attempt used pioarduino `55.03.311` (Arduino 3.3.11 / ESP-IDF 5.5.5). Source compilation passed, but final linking failed because NimBLE referenced C6 controller ROM memory symbols such as `r_os_mempool_init`, `r_os_memblock_get`, and `r_os_memblock_put` that were not exported. Pinning the tested 5.4.2 platform fixed the link without patching vendor code. Re-test this constraint before upgrading the platform.

## 2026-07-25 — Restore the original standalone installer workflow

### What changed

- Added a verified, matching set of ESP32-C6 bootloader, partition, OTA-data, and FlockYou application binaries under `firmware/`.
- Changed `requirements.txt` back to minimal installer dependencies (`esptool` and `pyserial`) and moved PlatformIO into `requirements-dev.txt`.
- Updated the README and flashing guide so ordinary installation uses `python3 flash.py`, like the original OuiSpy workflow, with no global `pio` command required.
- Expanded the README installer directions to match the main repository's structure: prerequisites, dependency setup, single-board flashing, verification, command options, and troubleshooting.
- Changed the standalone flasher from forced QIO to DIO after physical testing showed that QIO rewrote the DIO image header and caused a bootloader watchdog loop.

### Verification

- Flashed all four images to ESP32-C6 revision 0.2 through `flash.py`; every image passed write-hash verification.
- Serial boot confirmed SPIFFS, BLE scanning, buzzer startup, `flockyou` AP, captive DNS, and the web server.
- Dashboard and real-world detection behavior remain to be checked by the user.

## Entry template

Copy this section for later work:

```markdown
## YYYY-MM-DD — Short change title

### What changed

- ...

### Why

- ...

### Verification

- Build/test/hardware evidence...
- Explicitly list anything not tested.
```
