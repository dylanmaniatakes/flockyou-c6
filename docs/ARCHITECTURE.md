# Firmware architecture and modification guide

## Design goal

This is a single-purpose BLE surveillance-device detector. It keeps the upstream FlockYou matching and export behavior while removing the unified firmware selector and every unrelated mode. The entire runtime is in `src/main.cpp` so a contributor can trace behavior without following wrapper files or copied “raw” sources.

## Startup flow

`setup()` performs these steps in order:

1. Starts USB serial at 115200 baud.
2. Reads the persisted buzzer preference from the existing `ouispy-bz` NVS namespace. The default is enabled.
3. Configures the buzzer on the XIAO header alias `D2`.
4. Creates one mutex for detection records and another for phone-location state.
5. Mounts SPIFFS and promotes the prior session so it remains available through the history APIs.
6. Initializes NimBLE as a scanner, installs `FYBLECallbacks`, and starts the first active scan.
7. Plays the startup crow sequence after scanning has started.
8. Starts the `flockyou` Wi-Fi access point, captive DNS, HTTP dashboard, and API routes.

Starting BLE before the sound and web server minimizes the period after boot in which nearby advertisements could be missed.

## Main loop

`loop()` is intentionally small. It services captive DNS, restarts the BLE scan on its cadence, clears completed scan results, manages periodic buzzer heartbeats, backfills fresh phone coordinates, and persists changed detection sessions.

The BLE callback is asynchronous relative to HTTP requests. Shared arrays are never read or written without `fyMutex`; phone-location values use `fyGPSMutex`. Keep this locking split when extending the code. Taking one small mutex for unrelated state would increase the time-sensitive BLE callback's chance of blocking behind an export request.

## Detection pipeline

Every completed advertisement reaches `FYBLECallbacks::onResult()`. Checks run in this order and stop at the first match:

1. MAC/OUI prefix (`mac_prefix`)
2. case-insensitive advertised name (`device_name`)
3. manufacturer company ID (`ble_mfr_id`)
4. Raven service UUID (`raven_uuid`)

A matching advertisement updates an existing record with the same MAC address or appends a new record until `MAX_DETECTIONS` is reached. Each record stores name, latest RSSI, match method, first/last timestamps, sighting count, Raven metadata, and optional phone coordinates.

The ordering affects the reported `method`. A device matching both an OUI and a name is recorded as `mac_prefix`. Change that ordering only deliberately and log the output-contract change.

## Raven version estimate

`estimateRavenFW()` infers a firmware family from advertised service combinations:

- old location without the newer GPS service → `1.1.x`;
- newer GPS without power service → `1.2.x`;
- newer GPS plus power service → `1.3.x`.

These are estimates, not a GATT firmware read. Preserve that distinction in documentation and UI changes.

## Buzzer behavior

The buzzer has three semantic patterns:

- boot: three crow-like descending calls;
- new in-range event: two rising chirps followed by a descending call;
- heartbeat: a softer double call every ten seconds while a matched device remains in range.

`fyTriggered` prevents every repeated advertisement from replaying the full alert. After 30 seconds without a matching sighting, the device is treated as out of range and a future sighting can trigger a new alert.

## Phone location

There is no hardware GPS. JavaScript in the dashboard may call the browser Geolocation API, then send `lat`, `lon`, and `acc` to `/api/gps`.

The ESP32 treats a phone location as fresh for 30 seconds. A fresh snapshot is attached on first sighting and subsequent sightings. `fyBackfillGPS()` can attach a newly available fix to earlier records. If location is absent or stale, detection continues without coordinates.

## Persistence model

Up to 200 unique detections are held in RAM. SPIFFS uses an envelope containing payload size and CRC32, followed by the JSON array. Saves go to `/session.tmp` first and are promoted to `/session.json`; this makes a sudden power loss less likely to destroy both the previous and current data.

The save policy is:

- first save within five seconds of an initial detection;
- save when the unique count changes, with a three-second minimum gap;
- safety save every 15 seconds while records exist.

At boot, the last valid session is promoted to `/prev_session.json` for history/export. Do not replace this with a single direct write unless loss of power during writing is acceptable.

## HTTP API

| Route | Purpose |
|---|---|
| `/` | Embedded dashboard |
| `/api/detections` | Current detections as JSON |
| `/api/stats` | Counts, BLE state, and phone-location freshness |
| `/api/gps` | Receives optional browser location |
| `/api/patterns` | Current OUI/name/manufacturer/Raven tables |
| `/api/export/json` | Current session JSON download |
| `/api/export/csv` | Current session CSV download |
| `/api/export/kml` | GPS-tagged current session KML |
| `/api/history` | Prior session JSON |
| `/api/history/json` | Prior session JSON download |
| `/api/history/kml` | Prior session converted to KML |
| `/api/clear` | Saves, then clears current in-memory detections |

Unknown routes redirect to `http://192.168.4.1/` for captive-portal behavior.

## C6-specific build decisions

- The environment is `seeed_xiao_esp32c6` at 160 MHz with 4 MB flash.
- A pinned pioarduino platform provides Arduino support for this board.
- Arduino-ESP32 3.2.1/ESP-IDF 5.4.2 is held because the tested NimBLE 2.5.0 combination links successfully on C6. The newer tested IDF 5.5.5 combination failed on missing controller ROM memory symbols.
- NimBLE uses the 2.x scan callback API: millisecond durations, `NimBLEScanCallbacks`, const advertised devices, and `setScanCallbacks()`.
- Unused NimBLE broadcaster, peripheral, and central roles are disabled; observer/scanner remains enabled.
- The 4 MB partition table provides almost 3 MB for one application and 1 MB for SPIFFS. There is no OTA updater, so a second app partition would waste needed space.

## Common modifications

### Add or remove detection patterns

Edit the tables near the top of `src/main.cpp`:

- `mac_prefixes`
- `device_name_patterns`
- `ble_manufacturer_ids`
- `raven_service_uuids`

Keep a source/provenance comment beside research-derived values. Note false-positive risk and whether a value was directly observed or inferred.

### Change Wi-Fi credentials

Edit `FY_AP_SSID` and `FY_AP_PASS`. WPA passwords must be at least eight characters. Update README and user-facing dashboard instructions in the same change.

### Change scan timing

Edit `BLE_SCAN_DURATION_MS` and `BLE_SCAN_INTERVAL_MS`. The scan window must remain no greater than the interval supplied to NimBLE. More scanning can improve observation opportunity but also increases radio contention with the Wi-Fi dashboard.

### Change the buzzer pin or sounds

Prefer a XIAO header alias (`D0` through `D10`) over a raw GPIO. Update `docs/HARDWARE.md`. Sound shapes are in `fyCaw()`, `fyBootBeep()`, `fyDetectBeep()`, and `fyHeartbeat()`.

### Change stored fields

Update all of the following together:

1. `FYDetection`;
2. `writeDetectionsJSON()`;
3. `fySerializeDet()`;
4. CSV/KML exports;
5. dashboard `card()` rendering;
6. the API documentation above.

Then verify CRC-backed session save/read behavior and prior-session parsing.

## Upstream maintenance

The Git remotes are intended to be:

```text
origin    dylanmaniatakes/flockyou-c6
upstream  colonelpanichacks/oui-spy-unified-blue
```

Upstream FlockYou changes will usually appear in its `src/raw/flockyou.cpp` plus wrapper. Port relevant changes manually into this fork's `src/main.cpp`; do not restore the unified selector or overwrite C6-specific pins/build settings. Record every adopted upstream change and source commit in `FORK_CHANGES.md`.

