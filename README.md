# FlockYou for XIAO ESP32-C6

> **A note from the fork maintainer:** This code was forked on July 25, 2026 by Dylan Maniatakes and modified with the help of AI vibecoding. I will be going through the changes as time allows. More self-respecting nerds should check the code before blindly trusting it. However in my limited testing so far, it seems to function. Im not ashamed to use tools that help me not have to write code, becuase with my terrible attention-span if i had to plug every line away, id never build anything for myself. 

This fork turns OuiSpy Unified Blue into a single-purpose FlockYou detector for the **Seeed Studio XIAO ESP32-C6**. The mode selector, Detector, Foxhunter, Sky Spy, ESP32-S3 binaries, NeoPixel support, and hardware-GPS support have been removed.

The only wired peripheral is a piezo buzzer on the XIAO header pin labeled **D2**. A phone can optionally contribute its browser location to detections; that feature does not require GPS hardware on the ESP32.

## Current status

- Compiles successfully for the XIAO ESP32-C6.
- Uses Arduino-ESP32 3.2.1 on ESP-IDF 5.4.2 and NimBLE-Arduino 2.5.0.
- Build verified on July 25, 2026: 74,044 bytes RAM and 1,306,979 bytes flash.
- Flashed and boot-verified on a physical ESP32-C6 revision 0.2: SPIFFS, BLE scanning, buzzer, access point, captive DNS, and web server all initialized successfully.
- Detection matching still needs field testing against known advertisements.

## Wiring

| XIAO pin label | ESP32-C6 GPIO | Connection |
|---|---:|---|
| D2 | GPIO2 | Piezo buzzer signal/positive |
| GND | — | Piezo buzzer ground/negative |

The source uses `D2`, not a hard-coded GPIO number. This matters because D2 is GPIO3 on the original XIAO ESP32-S3 but GPIO2 on the XIAO ESP32-C6. Keeping the header label preserves the physical wiring position. See [docs/HARDWARE.md](docs/HARDWARE.md) before changing pins.

## Flashing

Like the main OuiSpy repository, this fork includes everything needed to flash a board. PlatformIO and compiler tools are not required unless you want to modify and rebuild the firmware.

### What you need

- Python 3.8 or newer.
- A USB-C **data** cable, not a charge-only cable.
- The Seeed Studio XIAO ESP32-C6 connected to the computer.

### Step 1: Install the flasher dependencies

From this repository's directory, run:

```bash
python3 -m pip install -r requirements.txt
```

On systems where the commands are named `python` and `pip`, this is equivalent to:

```bash
pip install -r requirements.txt
```

If macOS or Linux refuses a system-wide Python package installation, use an isolated environment:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

### Step 2: Flash one board

With the XIAO ESP32-C6 connected, run:

```bash
python3 flash.py
```

If you used the isolated environment above, run:

```bash
.venv/bin/python flash.py
```

On Windows, use `.venv\Scripts\python flash.py`.

The script detects the serial port and included `flockyou-c6.bin`, shows the selected board and flash settings, then asks `Flash? [Y/n]`. Press Enter or type `y`. It writes the C6 bootloader, partition table, OTA data, and application, verifies every image, and reboots the board.

If automatic reset does not connect, hold **BOOT**, briefly press **RESET**, release **BOOT**, and run the command again.

### Step 3: Verify the installation

1. Listen for the crow-like startup sound from the buzzer.
2. Find the Wi-Fi network `flockyou`.
3. Connect with password `flockyou123`.
4. Open `http://192.168.4.1` in a browser.
5. Confirm that the FlockYou dashboard loads and refreshes.

### Flasher options

```bash
python3 flash.py                  # Flash one board interactively
python3 flash.py --erase          # Erase the entire chip before flashing
python3 flash.py --batch          # Flash multiple boards one after another
python3 flash.py --batch --erase  # Batch mode with a full erase per board
python3 flash.py --help           # Display the command reference
```

### Flashing troubleshooting

| Problem | Suggested fix |
|---|---|
| `python3: command not found` | Install Python 3, or try `python` if that is its name on your system. |
| `pip` or an externally-managed-environment error | Use the `.venv` commands above. |
| `esptool not found` | Run `python3 -m pip install -r requirements.txt` with the same Python used to launch `flash.py`. |
| No ESP32 detected | Check that the cable supports data, try another USB port, and use the BOOT/RESET procedure above. |
| More than one serial device found | Select the XIAO's USB JTAG/serial port from the list. |
| Flash succeeds but the board repeatedly resets | Confirm that all files came from this fork's `firmware/` directory. Do not mix ESP32-S3 and ESP32-C6 binaries. |

## Rebuild and upload for development

Install Python 3 and PlatformIO, then run:

```bash
python3 -m pip install -r requirements-dev.txt
pio run
pio run -t upload
pio device monitor -b 115200
```

The first build downloads the pinned C6 compiler, Arduino framework, and libraries. The application binary is written to:

```text
.pio/build/seeed_xiao_esp32c6/firmware.bin
```

If normal uploading cannot find the board, hold **BOOT**, connect USB, release **BOOT**, and retry. This is the XIAO ESP32-C6 bootloader procedure.

## Use

After boot, listen for the crow-like buzzer sequence, then connect to:

| Setting | Value |
|---|---|
| Wi-Fi SSID | `flockyou` |
| Wi-Fi password | `flockyou123` |
| Dashboard | `http://192.168.4.1` |

FlockYou scans BLE advertisements and checks them against:

- known Flock Safety MAC prefixes;
- device-name patterns such as `FS Ext Battery`, `Penguin`, `Flock`, and `Pigvision`;
- manufacturer company ID `0x09C8`;
- Raven service UUIDs, including UUID combinations used to estimate Raven firmware families.

Detections are kept in memory, periodically persisted to SPIFFS, shown in the dashboard, logged as JSON over serial, and exportable as JSON, CSV, or KML.

## Optional phone location

The GPS card in the dashboard asks the connected phone's browser for location and sends it to the ESP32. There is no GPS module attached to the C6.

Browser geolocation generally requires a secure context. Android Chrome can be configured to treat `http://192.168.4.1` as secure through `chrome://flags` → **Insecure origins treated as secure**. iOS Safari does not expose geolocation to this HTTP page.

The detector still works normally when phone location is unavailable; detections simply have no coordinates.

## Source layout

| Path | Purpose |
|---|---|
| `src/main.cpp` | Complete firmware: BLE matching, buzzer, web UI/API, exports, and persistence |
| `platformio.ini` | Reproducible C6 toolchain and library versions |
| `partitions.csv` | 4 MB C6 flash layout: one app plus 1 MB SPIFFS |
| `flash.py` | Standalone full-image flasher for prepared C6 binaries |
| `requirements.txt` | Minimal dependencies for the standalone flasher |
| `requirements-dev.txt` | PlatformIO plus the flasher dependencies |
| `scripts/package_firmware.py` | Copies a PlatformIO build into the `firmware/` layout used by `flash.py` |
| `docs/ARCHITECTURE.md` | Human-readable code flow and modification guide |
| `docs/HARDWARE.md` | Wiring and S3-to-C6 pin mapping notes |
| `FORK_CHANGES.md` | Running record of every change from upstream |

## Refreshing the bundled installer binaries

After modifying and successfully testing the firmware, maintainers can refresh the self-contained binaries used by the main-repo-style installer:

```bash
pio run
python3 scripts/package_firmware.py
python3 flash.py
```

Never reuse the deleted OuiSpy ESP32-S3 binaries on the C6. Bootloaders, flash size, partition layout, and application machine code are board-specific.

## Documentation for contributors

Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before changing detection or persistence behavior. Record each logical change in [FORK_CHANGES.md](FORK_CHANGES.md) so the fork can later produce an accurate differences section without reconstructing history from commits.

This repository remains based on [colonelpanichacks/oui-spy-unified-blue](https://github.com/colonelpanichacks/oui-spy-unified-blue). Detection-pattern credits and provenance are retained next to the corresponding tables in `src/main.cpp`.
