#!/usr/bin/env python3
"""
FlockYou C6 -- Firmware Flasher (Seeed Studio XIAO ESP32-C6)

Drop your .bin in the firmware/ folder (or pass a path), plug in your
XIAO ESP32-C6, and run:

    python flash.py

Supports batch flashing -- after each board finishes, swap it out and
press Enter to flash the next one. Great for production runs.

Works on macOS, Linux, and Windows.

Requirements:  pip install esptool pyserial
"""

import glob
import io
import os
import sys
import time
import subprocess
import serial.tools.list_ports

# Force UTF-8 on Windows so box-drawing / special chars don't garble
if sys.platform == "win32":
    os.system("")  # enable ANSI/VT100 on Win10+ terminals
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    else:
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
        sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

# -- Config ----------------------------------------------------------------
# XIAO ESP32-C6: RISC-V, Wi-Fi + BLE 5, 4 MB flash, no PSRAM.
# These values must remain synchronized with platformio.ini, partitions.csv,
# and a verbose `pio run -t upload -v` command. The support binaries are not
# interchangeable with ESP32-S3 binaries even when their filenames match.
BOOT_OFFSET   = "0x0000"
PART_OFFSET   = "0x8000"
OTA_OFFSET    = "0xe000"
APP_OFFSET    = "0x10000"
BAUD          = "460800"
CHIP          = "esp32c6"
# The C6 images produced by this pinned toolchain declare DIO in their image
# headers. Keep the standalone flasher aligned with those headers: forcing QIO
# rewrites the bootloader header and causes an early watchdog-reset loop on the
# physical XIAO ESP32-C6 before the application can start.
FLASH_MODE    = "dio"
FLASH_FREQ    = "80m"
FLASH_SIZE    = "4MB"
FIRMWARE_DIR  = os.path.join(os.path.dirname(os.path.abspath(__file__)), "firmware")

# Known USB vendor IDs for the XIAO C6 and common UART bridges.
ESP_VIDS = {
    "303A",  # Espressif USB JTAG/serial
    "2886",  # Seeed Studio native USB
    "1A86",  # CH340/CH341
    "10C4",  # CP210x
    "0403",  # FTDI
}

BANNER = """
  +==========================================+
  |       FlockYou -- XIAO C6 Flasher        |
  +==========================================+"""


def find_esp_candidates():
    """Return list of ESP32 serial port candidates, best match first.
    Native USB (Espressif VID 303A or Seeed VID 2886) is prioritized over
    generic UART bridges because that is the expected XIAO C6 interface.
    """
    ports = serial.tools.list_ports.comports()
    espressif = []   # Native USB on supported XIAO/ESP boards
    others = []      # CH340, CP210x, FTDI, generic matches
    for p in ports:
        vid = f"{p.vid:04X}" if p.vid else ""
        if vid in {"303A", "2886"}:
            espressif.append(p)
        elif vid in ESP_VIDS:
            others.append(p)
        elif "esp" in (p.description or "").lower():
            others.append(p)
        # macOS
        elif "usbmodem" in (p.device or "").lower():
            others.append(p)
        elif "usbserial" in (p.device or "").lower():
            others.append(p)
        # Linux
        elif "ttyACM" in (p.device or ""):
            others.append(p)
        elif "ttyUSB" in (p.device or ""):
            others.append(p)
        # Windows -- COM ports with a real description (skip built-in COM1)
        elif sys.platform == "win32" and (p.device or "").upper().startswith("COM"):
            port_num = p.device.upper().replace("COM", "")
            if port_num.isdigit() and int(port_num) > 1:
                others.append(p)
    # Espressif native USB first, then generic UART bridges
    return espressif + others


def find_port(auto_pick=False):
    """Auto-detect the ESP32 serial port (macOS, Linux, Windows).
    If auto_pick=True, only match Espressif/Seeed native USB to avoid grabbing
    random UART adapters. Interactive mode can fall back to all candidates.
    """
    candidates = find_esp_candidates()

    if auto_pick:
        # In batch mode, ONLY use XIAO/ESP native USB ports. This prevents
        # accidentally selecting an unrelated UART adapter on the same desk.
        native = [p for p in candidates
                  if p.vid and f"{p.vid:04X}" in {"303A", "2886"}]
        if len(native) >= 1:
            return native[0].device
        return None

    if len(candidates) == 1:
        return candidates[0].device
    if len(candidates) > 1:
        print("\n  Multiple serial ports found:\n")
        for i, p in enumerate(candidates):
            desc = p.description or "unknown"
            vid = f"{p.vid:04X}" if p.vid else "----"
            print(f"    [{i + 1}] {p.device}  ({desc})  VID:{vid}")
        print()
        while True:
            try:
                choice = input("  Pick a port [1]: ").strip()
                idx = int(choice) - 1 if choice else 0
                if 0 <= idx < len(candidates):
                    return candidates[idx].device
            except (ValueError, IndexError):
                pass
            print("  Invalid choice, try again.")
    return None


def wait_for_port(timeout=30, auto_pick=False):
    """Wait for an ESP32 to appear on USB. Returns port or None."""
    print(f"\n  Waiting for ESP32 (plug in a board)...", end="", flush=True)
    start = time.time()
    last_dot = start
    while time.time() - start < timeout:
        port = find_port(auto_pick=auto_pick)
        if port:
            print(f" found!")
            return port
        if time.time() - last_dot >= 2:
            print(".", end="", flush=True)
            last_dot = time.time()
        time.sleep(0.5)
    print(" timeout.")
    return None


def find_firmware(path_arg=None):
    """Locate the .bin file to flash."""
    # Explicit path from CLI
    if path_arg:
        if os.path.isfile(path_arg):
            return os.path.abspath(path_arg)
        print(f"\n  File not found: {path_arg}")
        sys.exit(1)

    # Look in firmware/ folder (exclude support bins -- flash_one handles those)
    SUPPORT_BINS = {"bootloader.bin", "partitions.bin", "boot_app0.bin"}
    if os.path.isdir(FIRMWARE_DIR):
        bins = sorted(
            [b for b in glob.glob(os.path.join(FIRMWARE_DIR, "*.bin"))
             if os.path.basename(b).lower() not in SUPPORT_BINS],
            key=os.path.getmtime, reverse=True,
        )
        if len(bins) == 1:
            return bins[0]
        if len(bins) > 1:
            print("\n  Multiple .bin files found:\n")
            for i, b in enumerate(bins):
                size = os.path.getsize(b) / 1024
                print(f"    [{i + 1}] {os.path.basename(b)}  ({size:.0f} KB)")
            print()
            while True:
                try:
                    choice = input("  Pick a firmware [1]: ").strip()
                    idx = int(choice) - 1 if choice else 0
                    if 0 <= idx < len(bins):
                        return bins[idx]
                except (ValueError, IndexError):
                    pass
                print("  Invalid choice, try again.")

    # Check current directory
    bins = sorted(glob.glob("*.bin"), key=os.path.getmtime, reverse=True)
    if bins:
        return os.path.abspath(bins[0])

    return None


def flash_one(port, firmware, do_erase=False, board_num=None):
    """Flash a single board. Returns True on success."""
    size_kb = os.path.getsize(firmware) / 1024
    label = f"  Board #{board_num}" if board_num else "  Target"

    # Look for bootloader + partitions + OTA data alongside the app binary
    fw_dir = os.path.dirname(firmware)
    bootloader = os.path.join(fw_dir, "bootloader.bin")
    partitions = os.path.join(fw_dir, "partitions.bin")
    ota_data = os.path.join(fw_dir, "boot_app0.bin")
    has_boot = os.path.isfile(bootloader)
    has_part = os.path.isfile(partitions)
    has_ota = os.path.isfile(ota_data)
    has_full = has_boot and has_part and has_ota

    if not has_full:
        missing = []
        if not has_boot: missing.append("bootloader.bin")
        if not has_part: missing.append("partitions.bin")
        if not has_ota:  missing.append("boot_app0.bin")
        print(f"\n  WARNING: Missing support bins: {', '.join(missing)}")
        print(f"  Flash may not boot correctly without all support files.")
        print(f"  Build with PlatformIO first to generate them.\n")

    print(f"""
{label}
  Port:       {port}
  Firmware:   {os.path.basename(firmware)}  ({size_kb:.0f} KB)
  Chip:       {CHIP}
  Flash mode: {FLASH_MODE} / {FLASH_FREQ} / {FLASH_SIZE}
  Full flash: {"YES (bootloader + partitions + OTA + app)" if has_full else "PARTIAL -- see warning above"}
  Baud:       {BAUD}
""")

    if do_erase:
        erase(port)

    print("  Flashing...\n")

    # Match PlatformIO's exact esptool invocation (esptool v5 dash syntax)
    cmd = [
        sys.executable, "-m", "esptool",
        "--chip", CHIP,
        "--port", port,
        "--baud", BAUD,
        "--before", "default-reset",
        "--after", "hard-reset",
        "write-flash",
        "-z",
        "--flash-mode", FLASH_MODE,
        "--flash-freq", FLASH_FREQ,
        "--flash-size", FLASH_SIZE,
    ]

    # Flash all support bins + app in one shot (order matters)
    if has_boot:
        cmd += [BOOT_OFFSET, bootloader]
    if has_part:
        cmd += [PART_OFFSET, partitions]
    if has_ota:
        cmd += [OTA_OFFSET, ota_data]
    cmd += [APP_OFFSET, firmware]

    try:
        result = subprocess.run(cmd)
        if result.returncode == 0:
            print("\n  Done! Device will reboot into the new firmware.")
            if sys.platform == "darwin":
                subprocess.Popen(["afplay", "/System/Library/Sounds/Hero.aiff"])
            return True
        else:
            print(f"\n  esptool exited with code {result.returncode}")
            if sys.platform == "darwin":
                subprocess.Popen(["afplay", "/System/Library/Sounds/Basso.aiff"])
            return False
    except FileNotFoundError:
        print("  esptool not found. Install it:\n")
        print("    pip install esptool\n")
        sys.exit(1)


def erase(port):
    """Full flash erase."""
    print(f"  Erasing flash on {port}...\n")
    cmd = [
        sys.executable, "-m", "esptool",
        "--chip", CHIP,
        "--port", port,
        "--baud", BAUD,
        "erase-flash",
    ]
    subprocess.run(cmd)
    print()


def wait_for_disconnect(old_port, timeout=30):
    """Wait for a specific port to disappear (board unplugged)."""
    start = time.time()
    while time.time() - start < timeout:
        ports = [p.device for p in serial.tools.list_ports.comports()]
        if old_port not in ports:
            return True
        time.sleep(0.3)
    return False


def batch_mode(firmware, do_erase=False):
    """Flash multiple boards one after another. Fully hands-free."""
    print(BANNER)
    size_kb = os.path.getsize(firmware) / 1024
    print(f"""
  BATCH MODE -- fully automatic
  Firmware:   {os.path.basename(firmware)}  ({size_kb:.0f} KB)
  Erase:      {"YES" if do_erase else "no"}

  Just plug in boards one at a time.
  Flashing starts automatically when a board is detected.
  Unplug when done, plug in the next one.
  Press Ctrl+C to stop.
""")

    board_num = 0
    success_count = 0
    fail_count = 0
    last_port = None

    while True:
        # If we just flashed a board, wait for it to be unplugged
        if last_port:
            print("  Waiting for board to be unplugged...", end="", flush=True)
            if wait_for_disconnect(last_port, timeout=120):
                print(" unplugged.")
            else:
                print(" timeout -- unplug the board and try again.")
                continue
            # Brief settle time after disconnect
            time.sleep(0.5)

        # Wait for a new board -- never give up, just keep polling
        port = None
        while not port:
            port = wait_for_port(timeout=60, auto_pick=True)
            if not port:
                print("  Still waiting... plug in a board (Ctrl+C to quit).")

        # Give the port a moment to stabilize (Windows especially needs this)
        time.sleep(1.5)

        board_num += 1
        ok = flash_one(port, firmware, do_erase=do_erase, board_num=board_num)
        if ok:
            success_count += 1
        else:
            fail_count += 1

        last_port = port
        print(f"\n  -- Score: {success_count} flashed, {fail_count} failed --")
        print(f"  Unplug this board and plug in the next one.")

    print(f"""
  +==========================================+
  |   Batch complete                         |
  +==========================================+
  |   Flashed:  {success_count:<5}                        |
  |   Failed:   {fail_count:<5}                        |
  +==========================================+
""")


def main():
    # Parse args
    args = sys.argv[1:]
    do_erase = "--erase" in args
    do_batch = "--batch" in args
    bin_path = None

    for a in args:
        if a in ("--erase", "--batch"):
            continue
        if a in ("-h", "--help"):
            print("""
  Usage:  python flash.py [firmware.bin] [--erase] [--batch]

  Options:
    firmware.bin   Path to .bin file (auto-detects from firmware/ folder)
    --erase        Erase entire flash before writing
    --batch        Batch mode: flash multiple boards one after another

  Single board (default):
    python flash.py

  Batch flash (production run):
    python flash.py --batch
    python flash.py --batch --erase

  Setup:
    pip install esptool pyserial
    mkdir firmware
    # drop bootloader.bin, partitions.bin, boot_app0.bin, and your app .bin
    python flash.py
""")
            sys.exit(0)
        bin_path = a

    # Check esptool is installed
    try:
        subprocess.run([sys.executable, "-m", "esptool", "version"],
                       capture_output=True, check=True)
    except (subprocess.CalledProcessError, FileNotFoundError):
        print("\n  esptool not found. Install it:\n")
        print("    pip install esptool\n")
        sys.exit(1)

    # Find firmware first (same for all boards)
    firmware = find_firmware(bin_path)
    if not firmware:
        print(f"\n  No .bin file found.")
        print(f"  Drop your firmware in:  {FIRMWARE_DIR}/")
        print(f"  Or pass it directly:    python flash.py my_firmware.bin\n")
        sys.exit(1)

    # Batch mode
    if do_batch:
        batch_mode(firmware, do_erase=do_erase)
        return

    # Single mode -- find port
    print(BANNER)
    port = find_port()
    if not port:
        print("\n  No ESP32 detected. Is the board plugged in?")
        print("  Make sure drivers are installed (CH340/CP210x).\n")
        sys.exit(1)

    print(f"  Found: {port}")

    confirm = input("\n  Flash? [Y/n]: ").strip().lower()
    if confirm and confirm != "y":
        print("  Aborted.")
        sys.exit(0)

    ok = flash_one(port, firmware, do_erase=do_erase)
    if not ok:
        sys.exit(1)

    # Offer to flash another
    try:
        resp = input("\n  Flash another board? [y/N]: ").strip().lower()
        if resp == "y":
            batch_mode(firmware, do_erase=do_erase)
    except (KeyboardInterrupt, EOFError):
        pass

    print()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n  Bye!\n")
        sys.exit(0)
