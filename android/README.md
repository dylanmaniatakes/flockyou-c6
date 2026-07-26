# FlockYou Android Companion

The Android companion solves the browser GPS problem without adding TLS or a
GPS receiver to the ESP32. Android supplies location through its native
permission system; the app forwards latitude, longitude, and reported accuracy
to the firmware's existing `http://192.168.4.1/api/gps` endpoint.

Version `0.2.0` is intentionally small and uses only Android platform APIs. It
does not include advertising, analytics, accounts, cloud services, or an
Internet API.

## Requirements

- Android 10 or newer (API 29+).
- FlockYou firmware running on the XIAO ESP32-C6.
- Location enabled on the phone.
- Precise location permission is recommended; approximate location still works
  but produces much less useful detection coordinates.

## Install the current debug build

The unsigned development APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with Android Debug Bridge:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, copy the APK to an Android phone, allow installation from the
chosen file-manager source when Android asks, and open the file. Debug APKs are
for local testing and should not be published as releases.

## Use

1. Power the ESP32-C6 and wait for the `flockyou` access point.
2. Open **FlockYou Companion**.
3. Tap **CONNECT** and approve Android's Wi-Fi connection dialog. If the phone
   cannot find the network, join `flockyou` manually using password
   `flockyou123`, then tap **RELOAD**.
4. Tap **START GPS** and grant location and notification permissions. Choose
   precise location when available.
5. The app explains why unrestricted battery use helps, then offers Android's
   standard exemption prompt. Accept it for the most reliable screen-off
   tracking, or decline and continue with normal Android battery management.
6. A persistent **FlockYou Companion active** notification means the location
   foreground service is running. The notification has a **Stop** action.
7. You can turn off the screen or leave the app. The service continues polling
   location roughly every five seconds and sends updates when Android supplies
   them. The ESP32 buzzer continues operating independently.
8. Reopen the app when the buzzer alerts to see the dashboard. Tap **STOP GPS**
   when the wardriving session is finished.
9. The dashboard's own GPS card also starts the native Android GPS bridge when
   viewed inside this app; it does not invoke the HTTPS-only browser API.

JSON, CSV, and KML export buttons download through the ESP-bound connection and
save into Android's public Downloads collection.

## Build

From the `android/` directory:

```bash
./gradlew assembleDebug
./gradlew lintDebug
```

The project pins Android Gradle Plugin 8.13.2 and Gradle 8.13, compiles against
Android API 36, and emits Java 17 bytecode. No Android Studio project import is
required for command-line builds, although Android Studio can open this folder.

## Privacy and security boundaries

- Location comes from Android `LocationManager`, never from JavaScript.
- Only latitude, longitude, and accuracy are sent to the ESP32.
- Updates use the existing local HTTP endpoint and never leave the
  `192.168.4.1` device.
- Background location runs as a visible Android location foreground service,
  started only from a user tap while the activity is visible.
- A persistent notification and both app and notification Stop controls make
  the active session visible and reversible.
- HTTP is permitted because the ESP has no trusted hostname or certificate.
  The WebView rejects navigation and downloads whose scheme or host differs
  from `http://192.168.4.1`.
- Android backup and device transfer are disabled for app data.
- The JavaScript bridge exposes only a command to start native location. It
  does not expose coordinates, files, Android objects, or arbitrary HTTP.

## Current limitations

- Android owns the Wi-Fi approval dialog and may require manual connection on
  devices that do not support concurrent local-only Wi-Fi.
- Battery exemptions are advisory: some Android manufacturers add their own
  background controls. If updates stop, open the app's Android battery settings
  and select **Unrestricted** or **Allow background usage**.
- The service is sticky and Android may recreate it after ordinary process
  pressure, but it does not auto-start after a phone reboot. The user must open
  the app and start a new session after rebooting.
- The app has compile/lint verification and an Android 15 emulator smoke test,
  including Connect, service/notification lifecycle, battery exemption,
  backgrounding, simulated coordinates, state restoration, and Stop behavior.
  ESP networking and real GNSS still need continued physical-phone testing.
- The APK is debug-signed. A distributable release needs a maintainer-owned
  signing key and an explicit release process.

See [`../docs/ANDROID_APP.md`](../docs/ANDROID_APP.md) for implementation details.
