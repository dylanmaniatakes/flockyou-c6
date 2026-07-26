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

## 2026-07-25 — Begin native Android GPS companion

### What changed

- Added a native Java Android application under `android/`, targeting Android 10 and newer with no third-party runtime libraries.
- Added a system-managed `WifiNetworkSpecifier` connection request for SSID `flockyou` and bound only the companion app to the returned local network.
- Embedded the existing ESP dashboard in a host-restricted WebView.
- Replaced the dashboard GPS-card behavior inside the app with native Android location permission and `LocationManager` updates, avoiding the browser's HTTPS-only Geolocation API.
- Forwarded latitude, longitude, and Android-reported accuracy to the existing `/api/gps` firmware endpoint.
- Added native handling for dashboard JSON, CSV, and KML downloads through the ESP-bound network.
- Restricted WebView navigation and downloads to the exact local origin `http://192.168.4.1` and disabled app-data backup.
- Added detailed app usage, architecture, permission, cleartext-HTTP, privacy, and future foreground-service documentation.

### Current scope

- Version `0.1.0` sends location only while the app activity is visible.
- It does not request background location or pretend to support screen-off wardriving.
- A future foreground-service implementation must include a persistent notification, stop control, current Android service permissions, and physical-device power-policy testing.

### Verification

- `./gradlew clean assembleDebug lintDebug`: successful.
- Debug APK produced for package `com.dylanmaniatakes.flockyou`, minimum API 29, target API 36.
- Android lint: zero errors; one informational warning records that the project intentionally pins Gradle 8.13 instead of automatically following 8.14.5.
- Android 15 emulator: APK installation, cold launch, edge-to-edge layout, location tracking state, and simulated-coordinate handling passed without a crash.
- The simulated coordinate correctly reached the HTTP sender and reported that the ESP was unreachable from the emulator, exercising the expected failure path.
- Physical Android Wi-Fi connection, real GPS forwarding, live WebView content, and export downloads are not yet tested because no Android device was connected to ADB.

## 2026-07-25 — Android 0.2.0 background GPS and Connect crash fix

### Connect crash

- Added the missing `CHANGE_NETWORK_STATE` manifest permission required by `ConnectivityManager.requestNetwork()`. Without it, tapping Connect could throw an uncaught `SecurityException` on a physical phone.
- Moved the network request into the service and wrapped both `SecurityException` and other runtime failures so Android or manufacturer-specific rejection becomes a visible status instead of an app crash.

### Background operation

- Added `GpsForwardingService`, a user-visible Android location foreground service that owns Wi-Fi, native location listeners, and GPS delivery to the ESP.
- Declared `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, notification, and battery-exemption permissions required by current Android releases.
- Added an ongoing low-priority notification with a Stop action.
- Changed GPS updates to a nominal five-second / one-meter request and throttled endpoint sends to at most one every four seconds.
- Made an explicitly started tracking session sticky across ordinary process recreation while deliberately avoiding automatic start after phone reboot.
- Removed activity-lifecycle GPS cleanup. Closing the dashboard, pressing Home, or turning off the screen no longer intentionally stops the service.
- Kept `ACCESS_BACKGROUND_LOCATION` out of the app: the location foreground service is started by a visible user action and stays visible through its notification.

### Battery handling

- Added an explanation dialog before Android's standard unrestricted-battery confirmation.
- The app cannot grant itself an exemption; the user may allow or deny the request, and the decision remains reversible in Android settings.
- Recorded that manufacturer-specific background restrictions can still require manual configuration.

### Internal security and state

- Added a signature-only internal permission plus `RECEIVER_NOT_EXPORTED` on Android 13+ for service-to-activity status broadcasts.
- Stored only connection/tracking status needed to restore controls when the dashboard activity reopens. Coordinates remain unstored by the Android app.

### Verification

- `./gradlew assembleDebug lintDebug`: successful with zero lint errors.
- Connect no longer crashes in the Android 15 emulator and opens Android's system Wi-Fi selection UI.
- Verified foreground-service type, ongoing notification, sticky tracking state, simulated coordinate processing after pressing Home, and activity state restoration.
- Verified both the in-app battery explanation and Android's system-owned “always run in background” prompt; the emulator granted the exemption.
- Verified **STOP GPS** removes the service and clears tracking state.
- Lint retains two intentional warnings: the pinned Gradle version and Play-policy caution for direct battery-exemption requests in a sideloaded utility.
- Real ESP Wi-Fi, physical GNSS, screen-off duration, and manufacturer battery behavior still require physical-phone testing.

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
