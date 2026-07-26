package com.dylanmaniatakes.flockyou;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the ESP Wi-Fi request and Android location updates after the activity is
 * closed or the screen turns off.
 *
 * <p>This is a user-visible location foreground service, not a hidden
 * background process. Android displays an ongoing notification with a Stop
 * action for as long as the service may access location.</p>
 */
@SuppressWarnings("deprecation")
public final class GpsForwardingService extends Service implements LocationListener {
    public static final String ACTION_CONNECT =
            "com.dylanmaniatakes.flockyou.action.CONNECT";
    public static final String ACTION_START_TRACKING =
            "com.dylanmaniatakes.flockyou.action.START_TRACKING";
    public static final String ACTION_STOP =
            "com.dylanmaniatakes.flockyou.action.STOP";
    public static final String ACTION_STATUS =
            "com.dylanmaniatakes.flockyou.action.STATUS";
    public static final String STATUS_PERMISSION =
            "com.dylanmaniatakes.flockyou.permission.INTERNAL_STATUS";

    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_TRACKING = "tracking";

    public static final String PREFS_NAME = "gps_service_state";
    public static final String PREF_STATUS = "status";
    public static final String PREF_CONNECTED = "connected";
    public static final String PREF_TRACKING = "tracking";

    private static final String ESP_BASE_URL = "http://192.168.4.1";
    private static final String ESP_SSID = "flockyou";
    private static final String ESP_PASSWORD = "flockyou123";
    private static final String CHANNEL_ID = "flockyou_background_gps";
    private static final int NOTIFICATION_ID = 2001;
    private static final long LOCATION_INTERVAL_MS = 5_000L;
    private static final float LOCATION_MIN_DISTANCE_M = 1.0f;
    private static final long SEND_THROTTLE_MS = 4_000L;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private LocationManager locationManager;
    private SharedPreferences state;
    private Network espNetwork;
    private boolean tracking;
    private long lastSendElapsed;

    @Override
    public void onCreate() {
        super.onCreate();
        connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        state = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopEverything("Background GPS stopped");
            return START_NOT_STICKY;
        }

        // A sticky restart has a null intent. Restore only an explicitly
        // requested tracking session; a one-time CONNECT tap is not persistent.
        boolean shouldTrack = ACTION_START_TRACKING.equals(action)
                || state.getBoolean(PREF_TRACKING, false);

        startAsForeground(shouldTrack
                ? getString(R.string.gps_notification_starting)
                : "Connecting to flockyou…");
        requestEspNetwork();

        if (shouldTrack) {
            startLocationUpdates();
        } else {
            publishStatus("Waiting for Android Wi-Fi approval…", false, false);
        }

        return shouldTrack ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.gps_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when FlockYou is forwarding Android location to the ESP32");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String message) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, GpsForwardingService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Action stopAction = new Notification.Action.Builder(
                android.R.drawable.ic_media_pause,
                getString(R.string.gps_notification_stop),
                stopPendingIntent).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_flockyou)
                .setContentTitle(getString(R.string.gps_notification_title))
                .setContentText(message)
                .setContentIntent(openPendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(stopAction)
                .build();
    }

    private void startAsForeground(String message) {
        startForeground(
                NOTIFICATION_ID,
                buildNotification(message),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(message));
    }

    /**
     * Request the local-only ESP network. All exceptions are converted to a
     * visible status instead of escaping and crashing the app.
     */
    @SuppressLint("MissingPermission")
    private void requestEspNetwork() {
        if (espNetwork != null || networkCallback != null) {
            return;
        }

        try {
            WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                    .setSsid(ESP_SSID)
                    .setWpa2Passphrase(ESP_PASSWORD)
                    .build();
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    espNetwork = network;
                    connectivityManager.bindProcessToNetwork(network);
                    publishStatus(
                            tracking ? "Connected; waiting for GPS updates" : "Connected to flockyou",
                            true,
                            tracking);
                }

                @Override
                public void onLost(Network network) {
                    if (network.equals(espNetwork)) {
                        espNetwork = null;
                        connectivityManager.bindProcessToNetwork(null);
                        publishStatus("ESP Wi-Fi lost; GPS is still running", false, tracking);
                    }
                }

                @Override
                public void onUnavailable() {
                    networkCallback = null;
                    publishStatus(
                            "Could not connect automatically; join flockyou in Wi-Fi settings",
                            false,
                            tracking);
                }
            };

            connectivityManager.requestNetwork(request, networkCallback, 30_000);
        } catch (SecurityException error) {
            networkCallback = null;
            publishStatus(
                    "Wi-Fi permission rejected by Android; connect to flockyou manually",
                    false,
                    tracking);
        } catch (RuntimeException error) {
            networkCallback = null;
            publishStatus(
                    "Android could not request flockyou Wi-Fi: "
                            + error.getClass().getSimpleName(),
                    false,
                    tracking);
        }
    }

    @SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            publishStatus("Location permission is required", espNetwork != null, false);
            stopEverything("Location permission is required");
            return;
        }

        if (tracking) {
            return;
        }

        boolean requested = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        LOCATION_INTERVAL_MS,
                        LOCATION_MIN_DISTANCE_M,
                        this);
                requested = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        LOCATION_INTERVAL_MS,
                        LOCATION_MIN_DISTANCE_M,
                        this);
                requested = true;
            }
        } catch (SecurityException error) {
            publishStatus("Android revoked location permission", espNetwork != null, false);
            stopEverything("Android revoked location permission");
            return;
        }

        if (!requested) {
            publishStatus("Enable Android Location, then start GPS again", espNetwork != null, false);
            stopEverything("Android Location is disabled");
            return;
        }

        tracking = true;
        state.edit().putBoolean(PREF_TRACKING, true).apply();
        publishStatus("Background GPS active; waiting for a fix", espNetwork != null, true);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!tracking) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastSendElapsed < SEND_THROTTLE_MS) {
            return;
        }
        lastSendElapsed = now;
        postLocation(location);
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            publishStatus("Android GPS provider disabled", espNetwork != null, tracking);
        }
    }

    @Override public void onProviderEnabled(String provider) { }
    @Override public void onStatusChanged(String provider, int statusCode, Bundle extras) { }

    private void postLocation(Location location) {
        final double latitude = location.getLatitude();
        final double longitude = location.getLongitude();
        final float accuracy = location.hasAccuracy() ? location.getAccuracy() : 0.0f;

        ioExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL endpoint = new URL(String.format(Locale.US,
                        ESP_BASE_URL + "/api/gps?lat=%.8f&lon=%.8f&acc=%.1f",
                        latitude, longitude, accuracy));
                connection = espNetwork == null
                        ? (HttpURLConnection) endpoint.openConnection()
                        : (HttpURLConnection) espNetwork.openConnection(endpoint);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(2_500);
                connection.setReadTimeout(2_500);
                int response = connection.getResponseCode();
                if (response == HttpURLConnection.HTTP_OK) {
                    publishStatus(String.format(Locale.US,
                            "GPS sent: %.5f, %.5f (±%.0f m)",
                            latitude, longitude, accuracy), true, true);
                } else {
                    publishStatus("ESP rejected GPS update: HTTP " + response,
                            espNetwork != null, true);
                }
            } catch (Exception error) {
                publishStatus("GPS active; waiting for the ESP connection",
                        espNetwork != null, true);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void publishStatus(String message, boolean connected, boolean isTracking) {
        tracking = isTracking;
        state.edit()
                .putString(PREF_STATUS, message)
                .putBoolean(PREF_CONNECTED, connected)
                .putBoolean(PREF_TRACKING, isTracking)
                .apply();

        updateNotification(message);

        Intent update = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_CONNECTED, connected)
                .putExtra(EXTRA_TRACKING, isTracking);
        sendBroadcast(update, STATUS_PERMISSION);
    }

    private void stopEverything(String message) {
        tracking = false;
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
        releaseEspNetwork();
        state.edit()
                .putString(PREF_STATUS, message)
                .putBoolean(PREF_CONNECTED, false)
                .putBoolean(PREF_TRACKING, false)
                .apply();
        sendBroadcast(new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_CONNECTED, false)
                .putExtra(EXTRA_TRACKING, false), STATUS_PERMISSION);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseEspNetwork() {
        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (IllegalArgumentException ignored) {
                // Android already released a timed-out callback.
            }
            networkCallback = null;
        }
        espNetwork = null;
        if (connectivityManager != null) {
            connectivityManager.bindProcessToNetwork(null);
        }
    }

    @Override
    public void onDestroy() {
        tracking = false;
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
        releaseEspNetwork();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }
}
