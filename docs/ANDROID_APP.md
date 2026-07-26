# Android companion architecture

## Why an app is used instead of ESP-hosted HTTPS

Browser geolocation is restricted to secure contexts. The ESP32 dashboard is
served from the private IP `http://192.168.4.1` and has neither a stable public
hostname nor a certificate chain trusted by Android. Serving TLS with a
self-signed certificate would encrypt traffic, but it would not remove the
certificate warning or reliably make browser geolocation available without
installing a custom certificate authority on each phone.

The companion app avoids that trust-distribution problem. It requests location
from Android itself and sends a small HTTP request over the ESP's isolated
Wi-Fi network. The firmware API and dashboard remain usable without the app.

## Data flow

```text
Android LocationManager
        |
        | latitude, longitude, accuracy
        v
MainActivity.postLocation()
        |
        | GET http://192.168.4.1/api/gps?... 
        v
ESP32 fyGPSUpdate()
        |
        +--> new and repeated BLE detections receive coordinates
        +--> dashboard statistics report phone GPS
        +--> JSON, CSV, and KML exports include coordinates
```

No location is sent to a cloud service or stored by the Android app.

## Main components

### Native control strip

`MainActivity` creates three controls above the ESP dashboard:

- **CONNECT** asks Android to connect to SSID `flockyou` through
  `WifiNetworkSpecifier`.
- **START GPS / STOP GPS** controls the persistent location foreground service.
- **RELOAD** reloads `http://192.168.4.1` in the WebView.

The status line distinguishes waiting for permission, waiting for a fix,
successful ESP updates, and network errors.

### Foreground service ownership

`GpsForwardingService` owns both the Wi-Fi network request and location
listeners. Moving this state out of `MainActivity` is what permits the activity
and WebView to be closed without ending wardriving.

The service:

- is started only by a visible, user-initiated app action;
- declares Android's `location` foreground-service type;
- immediately publishes an ongoing low-priority notification;
- includes a notification Stop action;
- requests GPS and network-provider updates with a nominal five-second,
  one-meter threshold;
- throttles HTTP sends to no more than one every four seconds;
- uses `START_STICKY` only after GPS tracking was explicitly requested;
- records enough status for a recreated activity to restore its controls.

The ESP buzzer does not depend on the service or dashboard. The service keeps
coordinates flowing; the ESP continues scanning and sounding alerts itself.

### Local-only Wi-Fi

Android owns the network-selection confirmation dialog. After Android provides
the requested `Network`, the service binds its own process to that network. This is
deliberately process-scoped: it does not change routing for other phone apps.
HTTP requests are also opened directly through the returned `Network`, which
prevents Android from accidentally choosing cellular data for `192.168.4.1`.

### Native location

The app requests coarse and fine location together when the user taps a
location-dependent control. It listens to enabled GPS and network providers
with a nominal two-second / one-meter threshold. Android may deliver updates
at a different cadence based on hardware, permissions, and power policy.

The service remains a foreground component after `MainActivity.onStop()`, so
Android continues treating location as user-visible foreground work. It does
not request the separate `ACCESS_BACKGROUND_LOCATION` permission.

### Battery optimization

On the first tracking start, the activity explains why the session benefits
from unrestricted battery use. If the user continues, Android displays the
system-owned `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` confirmation. The app
cannot silently grant itself an exemption, and declining does not stop the
session.

The request is made once. Users can later change the choice from Android's app
or special-access battery settings. Manufacturer-specific task killers may
still require additional configuration.

### Dashboard bridge

The WebView loads the dashboard directly from the ESP. Once the page finishes,
the app replaces the page's `reqGPS()` JavaScript function with a call to the
small `FlockYouAndroid.startLocation()` bridge. This keeps the dashboard GPS
card useful without exposing native coordinates to JavaScript.

Navigation is allowed only when the URL scheme is HTTP and the host is exactly
`192.168.4.1`. Export downloads apply the same check before the native app
copies a response into Android's Downloads collection.

## Permissions

| Permission | Reason |
|---|---|
| `INTERNET` | Open the local HTTP dashboard and GPS endpoint |
| `ACCESS_NETWORK_STATE` | Observe the selected ESP network |
| `CHANGE_NETWORK_STATE` | Required by `ConnectivityManager.requestNetwork()`; omission caused the original Connect crash |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | Request the ESP access point |
| `ACCESS_COARSE_LOCATION` | Accept Android's approximate location choice |
| `ACCESS_FINE_LOCATION` | Produce useful wardriving coordinates when granted |
| `NEARBY_WIFI_DEVICES` | Request the local Wi-Fi network on Android 13+ |
| `FOREGROUND_SERVICE` | Run user-visible work after the activity closes |
| `FOREGROUND_SERVICE_LOCATION` | Declare the Android 14+ location service type |
| `POST_NOTIFICATIONS` | Show service status and the Stop action on Android 13+ |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Open Android's optional, user-controlled reliability exemption prompt |

There is no `ACCESS_BACKGROUND_LOCATION` permission. The location service is
started while the activity is visible and remains a user-visible foreground
service with an ongoing notification.

## Cleartext HTTP decision

`network_security_config.xml` permits HTTP because the ESP endpoint cannot
present a publicly trusted certificate. The permission is broad at Android's
transport-policy layer because numeric-IP scoping is not consistently handled
as a domain rule. Application code supplies the narrower boundary:

1. Dashboard navigation accepts only `http://192.168.4.1`.
2. Downloads accept only the same scheme and host.
3. GPS posts construct their URL from a private constant, not user input.
4. External navigation is rejected rather than opened in another app.

## Service lifecycle and remaining work

The service survives closing the dashboard, pressing Home, and turning off the
screen under standard Android foreground-service rules. Android can recreate a
sticky tracking service after ordinary process pressure. It intentionally does
not start after a phone reboot because a new location session should require a
fresh user action.

Further physical-device testing should cover:

- Wi-Fi loss and recovery while the screen is off;
- battery policies from Samsung, OnePlus, Motorola, and other vendors;
- long-drive GNSS accuracy and power consumption;
- notification Stop behavior across supported Android releases;
- whether a configurable polling interval is useful.
