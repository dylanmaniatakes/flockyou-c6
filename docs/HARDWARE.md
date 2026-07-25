# Hardware and wiring

## Supported build target

This fork targets the **Seeed Studio XIAO ESP32-C6**, not a generic ESP32-C6 module and not the original XIAO ESP32-S3. The board definition provides XIAO header aliases such as `D2`; use those aliases in source whenever the physical header position matters.

The C6 has 4 MB flash, no PSRAM, a 160 MHz RISC-V core, Wi-Fi, and BLE. Those limits drive the custom flash partition and the removal of unused firmware modes.

## Required connection

Connect the buzzer between **D2** and **GND**:

```text
XIAO ESP32-C6 D2  ----  buzzer signal/positive
XIAO ESP32-C6 GND ----  buzzer ground/negative
```

The firmware generates tones with Arduino's `tone()` API. A small passive piezo element can usually be driven directly. If the buzzer module draws more current than a GPIO should supply, drive it through an appropriate transistor and follow the module manufacturer's electrical limits.

## Why the code uses `D2`

The XIAO boards keep similar header labels while their underlying SoC GPIO assignments differ:

| Physical header | Original XIAO ESP32-S3 | XIAO ESP32-C6 |
|---|---:|---:|
| D2 | GPIO3 | GPIO2 |

The upstream code used raw `GPIO3`, which meant “D2” only on the S3. On the C6, raw GPIO3 controls part of the board's RF-switch circuit and is not the equivalent D2 header connection. The fork therefore defines:

```cpp
#define BUZZER_PIN D2
```

If the buzzer is moved, change this alias in the configuration block near the top of `src/main.cpp` and update this document plus `FORK_CHANGES.md`.

## Intentionally absent hardware

- No NeoPixel is initialized or referenced.
- No hardware GPS UART is initialized.
- No display is required.
- The BOOT button is used only by the ROM bootloader; the removed multi-mode selector no longer monitors it during normal operation.

The web dashboard's phone-location feature is network-only and does not consume a hardware pin.

## First-board bring-up checklist

1. Confirm the board is a XIAO ESP32-C6.
2. Confirm the buzzer is connected to D2 and GND with correct polarity when applicable.
3. Build and upload with PlatformIO.
4. Open a 115200-baud serial monitor.
5. Confirm the boot banner identifies FlockYou and BLE scanning becomes active.
6. Confirm the `flockyou` access point appears.
7. Open `http://192.168.4.1` and verify the dashboard refreshes.
8. Verify the startup and detection sounds at a safe volume.

Compilation confirms the target and APIs are valid, but it cannot prove RF coexistence, buzzer wiring, browser behavior, or real-world detection accuracy. Record physical results in `FORK_CHANGES.md` when tested.

