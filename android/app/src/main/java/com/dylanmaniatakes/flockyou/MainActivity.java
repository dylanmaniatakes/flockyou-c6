package com.dylanmaniatakes.flockyou;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native controls and host-restricted WebView for the FlockYou ESP dashboard.
 * Long-lived Wi-Fi and location work belongs to {@link GpsForwardingService};
 * this activity can be destroyed without ending an active wardriving session.
 */
public final class MainActivity extends Activity {
    private static final String ESP_HOST = "192.168.4.1";
    private static final String ESP_BASE_URL = "http://" + ESP_HOST;

    private static final int REQUEST_LOCATION = 1001;
    private static final int REQUEST_WIFI = 1002;
    private static final int REQUEST_NOTIFICATIONS = 1003;
    private static final int PENDING_NONE = 0;
    private static final int PENDING_CONNECT = 1;
    private static final int PENDING_TRACK = 2;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private WebView dashboard;
    private TextView status;
    private Button gpsButton;
    private boolean locationTracking;
    private boolean lastConnected;
    private boolean receiverRegistered;
    private int pendingAction = PENDING_NONE;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!GpsForwardingService.ACTION_STATUS.equals(intent.getAction())) {
                return;
            }
            String message = intent.getStringExtra(GpsForwardingService.EXTRA_MESSAGE);
            boolean connected = intent.getBooleanExtra(
                    GpsForwardingService.EXTRA_CONNECTED, false);
            boolean tracking = intent.getBooleanExtra(
                    GpsForwardingService.EXTRA_TRACKING, false);
            applyServiceState(message, connected, tracking);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        registerStatusReceiver();
        restoreServiceState();
        loadDashboard();
    }

    /** Build a small native control strip above the unchanged ESP dashboard. */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(10, 0, 18));
        applySystemBarInsets(root);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(6), dp(6), dp(6), dp(4));
        controls.setBackgroundColor(Color.rgb(26, 0, 51));

        Button connectButton = makeButton("CONNECT");
        connectButton.setOnClickListener(v -> beginServiceAction(PENDING_CONNECT));
        controls.addView(connectButton, weightedButtonParams());

        gpsButton = makeButton(getString(R.string.start_gps));
        gpsButton.setOnClickListener(v -> {
            if (locationTracking) {
                sendServiceAction(GpsForwardingService.ACTION_STOP);
            } else {
                beginServiceAction(PENDING_TRACK);
            }
        });
        controls.addView(gpsButton, weightedButtonParams());

        Button reloadButton = makeButton("RELOAD");
        reloadButton.setOnClickListener(v -> loadDashboard());
        controls.addView(reloadButton, weightedButtonParams());

        status = new TextView(this);
        status.setText(R.string.status_initial);
        status.setTextColor(Color.rgb(224, 224, 224));
        status.setTextSize(11);
        status.setPadding(dp(10), dp(3), dp(10), dp(6));
        status.setBackgroundColor(Color.rgb(26, 0, 51));

        dashboard = new WebView(this);
        WebSettings settings = dashboard.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        dashboard.addJavascriptInterface(new DashboardBridge(), "FlockYouAndroid");
        dashboard.setWebViewClient(new LocalOnlyWebViewClient());
        dashboard.setDownloadListener((url, userAgent, disposition, mimeType, size) ->
                downloadExport(url, disposition, mimeType));

        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(dashboard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        setContentView(root);
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(11);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.rgb(139, 92, 246));
        button.setAllCaps(false);
        return button;
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarInsets(LinearLayout root) {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(
                        windowInsets.getSystemWindowInsetLeft(),
                        windowInsets.getSystemWindowInsetTop(),
                        windowInsets.getSystemWindowInsetRight(),
                        windowInsets.getSystemWindowInsetBottom());
            }
            return windowInsets;
        });
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1.0f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Collect permissions in context, then start the foreground service while
     * this activity is visible as required by Android 14+.
     */
    private void beginServiceAction(int action) {
        pendingAction = action;
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQUEST_LOCATION);
            return;
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQUEST_WIFI);
            return;
        }

        if (action == PENDING_TRACK
                && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }

        launchPendingAction();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void launchPendingAction() {
        int action = pendingAction;
        pendingAction = PENDING_NONE;
        if (action == PENDING_CONNECT) {
            sendServiceAction(GpsForwardingService.ACTION_CONNECT);
        } else if (action == PENDING_TRACK) {
            sendServiceAction(GpsForwardingService.ACTION_START_TRACKING);
            maybePromptForUnrestrictedBattery();
        }
    }

    /** Start only from this visible activity and convert platform errors to UI. */
    private void sendServiceAction(String action) {
        try {
            Intent service = new Intent(this, GpsForwardingService.class).setAction(action);
            if (GpsForwardingService.ACTION_STOP.equals(action)) {
                startService(service);
            } else {
                startForegroundService(service);
                setStatus(GpsForwardingService.ACTION_START_TRACKING.equals(action)
                        ? "Starting background GPS…"
                        : "Requesting flockyou Wi-Fi…");
            }
        } catch (SecurityException | IllegalStateException error) {
            setStatus("Android refused the service: " + error.getClass().getSimpleName());
        }
    }

    /**
     * Ask once, after explaining why. Android owns the final unrestricted-
     * battery confirmation screen and the user may decline without losing the
     * foreground-only app experience.
     */
    private void maybePromptForUnrestrictedBattery() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        SharedPreferences preferences = getSharedPreferences("app_prompts", MODE_PRIVATE);
        if (powerManager.isIgnoringBatteryOptimizations(getPackageName())
                || preferences.getBoolean("battery_prompt_shown", false)) {
            return;
        }

        preferences.edit().putBoolean("battery_prompt_shown", true).apply();
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_dialog_title)
                .setMessage(R.string.battery_dialog_message)
                .setPositiveButton(R.string.battery_dialog_allow,
                        (dialog, which) -> openBatteryExemptionPrompt())
                .setNegativeButton(R.string.battery_dialog_later, null)
                .show();
    }

    private void openBatteryExemptionPrompt() {
        Intent request = new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(request);
        } catch (RuntimeException unavailable) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (RuntimeException ignored) {
                setStatus("Open Android App battery settings and choose Unrestricted");
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_LOCATION && !hasLocationPermission()) {
            pendingAction = PENDING_NONE;
            setStatus("Location permission denied; GPS forwarding is off");
            return;
        }
        if (requestCode == REQUEST_WIFI
                && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAction = PENDING_NONE;
            setStatus("Nearby Wi-Fi denied; join flockyou manually, then retry");
            return;
        }

        if (requestCode == REQUEST_NOTIFICATIONS) {
            // A denial does not invalidate the foreground service. Android
            // still exposes it through the system's active-apps interface.
            launchPendingAction();
            return;
        }

        beginServiceAction(pendingAction);
    }

    // The pre-Android-13 branch is protected by a signature-only permission;
    // the Android-13+ branch additionally supplies RECEIVER_NOT_EXPORTED.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStatusReceiver() {
        IntentFilter filter = new IntentFilter(GpsForwardingService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    statusReceiver,
                    filter,
                    GpsForwardingService.STATUS_PERMISSION,
                    null,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(
                    statusReceiver,
                    filter,
                    GpsForwardingService.STATUS_PERMISSION,
                    null);
        }
        receiverRegistered = true;
    }

    private void restoreServiceState() {
        SharedPreferences state = getSharedPreferences(
                GpsForwardingService.PREFS_NAME, MODE_PRIVATE);
        applyServiceState(
                state.getString(GpsForwardingService.PREF_STATUS,
                        getString(R.string.status_initial)),
                state.getBoolean(GpsForwardingService.PREF_CONNECTED, false),
                state.getBoolean(GpsForwardingService.PREF_TRACKING, false));
    }

    private void applyServiceState(String message, boolean connected, boolean tracking) {
        locationTracking = tracking;
        gpsButton.setText(tracking ? R.string.stop_gps : R.string.start_gps);
        if (message != null) {
            setStatus(message);
        }
        if (connected && !lastConnected) {
            loadDashboard();
        }
        lastConnected = connected;
    }

    private void loadDashboard() {
        if (dashboard != null) {
            dashboard.loadUrl(ESP_BASE_URL);
        }
    }

    /** Download JSON/CSV/KML after the service binds this process to ESP Wi-Fi. */
    private void downloadExport(String url, String disposition, String mimeType) {
        if (!isEspUrl(url)) {
            toast("Blocked a download outside the ESP dashboard");
            return;
        }

        setStatus("Downloading export…");
        ioExecutor.execute(() -> {
            HttpURLConnection connection = null;
            Uri destination = null;
            try {
                String guessed = URLUtil.guessFileName(url, disposition, mimeType);
                String filename = guessed.replaceAll("[^A-Za-z0-9._-]", "_");
                if (filename.isEmpty()) {
                    filename = "flockyou-export.json";
                }

                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(3_000);
                connection.setReadTimeout(10_000);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                }

                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE,
                        mimeType == null ? "application/octet-stream" : mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                destination = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destination == null) {
                    throw new IllegalStateException("Android could not create the download");
                }

                try (InputStream input = connection.getInputStream();
                     OutputStream output = getContentResolver().openOutputStream(destination)) {
                    if (output == null) {
                        throw new IllegalStateException("Android could not open the download");
                    }
                    byte[] buffer = new byte[8_192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, count);
                    }
                }

                final String savedName = filename;
                runOnUiThread(() -> {
                    setStatus("Saved " + savedName + " to Downloads");
                    toast("Saved " + savedName);
                });
            } catch (Exception error) {
                if (destination != null) {
                    getContentResolver().delete(destination, null, null);
                }
                final String message = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                runOnUiThread(() -> setStatus("Export failed: " + message));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private boolean isEspUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ESP_HOST.equals(uri.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void setStatus(String message) {
        if (status != null) {
            status.setText(message);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreServiceState();
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver);
            receiverRegistered = false;
        }
        if (dashboard != null) {
            dashboard.removeJavascriptInterface("FlockYouAndroid");
            dashboard.destroy();
        }
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    /** The bridge exposes only a start command, never coordinates or files. */
    private final class DashboardBridge {
        @JavascriptInterface
        public void startLocation() {
            runOnUiThread(() -> beginServiceAction(PENDING_TRACK));
        }
    }

    private final class LocalOnlyWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (isEspUrl(request.getUrl().toString())) {
                return false;
            }
            toast("Blocked navigation outside the ESP dashboard");
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (isEspUrl(url)) {
                view.evaluateJavascript(
                        "window.reqGPS=function(){FlockYouAndroid.startLocation();};", null);
                restoreServiceState();
            }
        }
    }
}
