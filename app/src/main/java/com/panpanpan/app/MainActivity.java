package com.panpanpan.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.midi.MidiDeviceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Home screen — shown ONLY after the permission gate passes.
 *
 * Layout:
 *   1. Connection status card (green/red + method)
 *   2. Start Playing button (gated by health monitor)
 *   3. MIDI devices list (toggle ON/OFF per device + refresh)
 *   4. Velocity toggle
 *   5. Force Close button (nuclear teardown)
 *
 * Permission Health Monitor runs continuously. If health drops
 * (permission revoked, daemon dies, etc.) → instant modal blocking UI,
 * user must re-authenticate (back to PermissionGate or Login).
 */
public class MainActivity extends Activity implements PermissionHealthMonitor.HealthCallback {

    public static final String TAG = "PapianoMidi";

    private View statusDot;
    private TextView tvConnectionStatus, tvConnectionMethod;
    private Button btnStart, btnRefreshMidi, btnForceClose;
    private LinearLayout midiDeviceList;
    private TextView tvNoDevices;
    private Switch swVelocity;

    private SharedPreferences sp;
    private PermissionHealthMonitor healthMonitor;

    private volatile boolean isServiceRunning = false;
    private boolean isBroadcastRegistered = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "papiano-bg");
        t.setDaemon(true);
        return t;
    });

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == null) return;
            switch (intent.getAction()) {
                case "intent.panpanpan.send_binder":
                    // Daemon ready — force health check
                    healthMonitor.forceCheck();
                    break;
                case "intent.panpanpan.exit":
                    healthMonitor.forceCheck();
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences("data", 0);

        // Unzip daemon files in background
        bg.execute(this::unzipFiles);

        bindViews();
        setupListeners();
        registerReceivers();

        // Start health monitor
        healthMonitor = new PermissionHealthMonitor(this);
        healthMonitor.setCallback(this);
        healthMonitor.start();

        // Request battery exemption in background
        ui.postDelayed(() -> bg.execute(this::requestBatteryExemption), 500);
    }

    private void bindViews() {
        statusDot = findViewById(R.id.status_dot);
        tvConnectionStatus = findViewById(R.id.tv_connection_status);
        tvConnectionMethod = findViewById(R.id.tv_connection_method);
        btnStart = findViewById(R.id.btn_start);
        btnRefreshMidi = findViewById(R.id.btn_refresh_midi);
        btnForceClose = findViewById(R.id.btn_force_close);
        midiDeviceList = findViewById(R.id.midi_device_list);
        tvNoDevices = findViewById(R.id.tv_no_devices);
        swVelocity = findViewById(R.id.sw_velocity);

        // Restore velocity state
        swVelocity.setChecked(sp.getBoolean("velocity_enabled", false));
    }

    private void setupListeners() {
        btnStart.setOnClickListener(v -> togglePlayback());

        btnRefreshMidi.setOnClickListener(v -> healthMonitor.forceCheck());

        btnForceClose.setOnClickListener(v ->
            BrutalPopup.dialog(this,
                "Force Close",
                "This will kill everything: daemon, service, session. You will need to re-authenticate on next launch.",
                "Force Close",
                this::performNuclearTeardown,
                "Cancel", null,
                null, null,
                true)
        );

        swVelocity.setOnCheckedChangeListener((btn, checked) -> {
            sp.edit().putBoolean("velocity_enabled", checked).apply();
            try {
                sendBroadcast(new Intent("intent.panpanpan.set_velocity")
                        .setPackage(getPackageName())
                        .putExtra("enabled", checked));
            } catch (Throwable t) {
                Log.w(TAG, "broadcast velocity", t);
            }
        });
    }

    private void registerReceivers() {
        try {
            IntentFilter f1 = new IntentFilter("intent.panpanpan.send_binder");
            ContextCompat.registerReceiver(this, receiver, f1,
                    ContextCompat.RECEIVER_EXPORTED);
            IntentFilter f2 = new IntentFilter("intent.panpanpan.exit");
            ContextCompat.registerReceiver(this, receiver, f2,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            isBroadcastRegistered = true;
        } catch (Throwable t) {
            Log.e(TAG, "registerReceiver", t);
        }
    }

    // ═══════════ HEALTH MONITOR CALLBACK ═══════════

    @Override
    public void onHealthChanged(PermissionHealthMonitor.Status status, String message,
                                List<PermissionHealthMonitor.MidiDeviceEntry> devices) {
        updateConnectionCard(status, message);
        updateMidiDeviceList(devices);
        updateStartButton(status);

        // If health drops while playing → show soft warning (NOT blocking modal)
        if (status == PermissionHealthMonitor.Status.UNHEALTHY && isServiceRunning) {
            isServiceRunning = false;
            BrutalPopup.toast(this, message, BrutalPopup.LENGTH_LONG);
        }
    }

    private void updateConnectionCard(PermissionHealthMonitor.Status status, String message) {
        if (status == PermissionHealthMonitor.Status.HEALTHY) {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.green)));
            tvConnectionStatus.setText("Active");
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
            String method = sp.getString("activation_method", "shizuku");
            tvConnectionMethod.setText("via " + capitalize(method));
        } else if (status == PermissionHealthMonitor.Status.RECOVERING) {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.accent_gold)));
            tvConnectionStatus.setText("Recovering...");
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_gold));
            tvConnectionMethod.setText(message);
        } else {
            statusDot.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.red)));
            tvConnectionStatus.setText("Disconnected");
            tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
            tvConnectionMethod.setText(message);
        }
    }

    private void updateStartButton(PermissionHealthMonitor.Status status) {
        if (status != PermissionHealthMonitor.Status.HEALTHY) {
            btnStart.setText("Waiting for connection...");
            btnStart.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.text_muted)));
            btnStart.setEnabled(false);
        } else {
            btnStart.setEnabled(true);
            if (isServiceRunning) {
                btnStart.setText("Stop Playing");
                btnStart.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.text_muted)));
            } else {
                btnStart.setText("Start Playing");
                btnStart.setBackgroundTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.accent_primary)));
            }
        }
    }

    private void updateMidiDeviceList(List<PermissionHealthMonitor.MidiDeviceEntry> devices) {
        midiDeviceList.removeAllViews();

        if (devices.isEmpty()) {
            tvNoDevices.setVisibility(View.VISIBLE);
            midiDeviceList.addView(tvNoDevices);
            return;
        }

        tvNoDevices.setVisibility(View.GONE);

        for (PermissionHealthMonitor.MidiDeviceEntry device : devices) {
            View row = LayoutInflater.from(this)
                    .inflate(R.layout.item_midi_device, midiDeviceList, false);

            TextView tvName = row.findViewById(R.id.tv_device_name);
            TextView tvBadge = row.findViewById(R.id.tv_device_badge);
            Switch swToggle = row.findViewById(R.id.sw_device_toggle);

            tvName.setText(device.name);
            tvBadge.setText(device.getTypeBadge());

            // Color-code badge by type
            int badgeColor;
            switch (device.type) {
                case MidiDeviceInfo.TYPE_USB:
                    badgeColor = R.color.green;
                    break;
                case MidiDeviceInfo.TYPE_BLUETOOTH:
                    badgeColor = R.color.accent_primary;
                    break;
                default:
                    badgeColor = R.color.text_muted;
                    break;
            }
            tvBadge.setTextColor(ContextCompat.getColor(this, badgeColor));

            swToggle.setChecked(device.enabled);
            swToggle.setOnCheckedChangeListener((btn, checked) -> {
                device.enabled = checked;
                // TODO: notify MidiBridgeService of device enable/disable
            });

            midiDeviceList.addView(row);
        }
    }

    // ═══════════ START / STOP PLAYBACK ═══════════

    private void togglePlayback() {
        if (isServiceRunning) {
            stopPlayback();
        } else {
            startPlayback();
        }
    }

    private void startPlayback() {
        // Check overlay permission
        if (!canDrawOverlays()) {
            showOverlayPermissionDialog();
            return;
        }

        bg.execute(() -> {
            try {
                enableAccessibilityService();
                ui.post(() -> {
                    isServiceRunning = true;
                    sp.edit().remove("panel_user_dismissed").apply();
                    startFloatingPanel();
                    updateStartButton(healthMonitor.getLastStatus());
                });
            } catch (Exception e) {
                Log.e(TAG, "startPlayback", e);
                ui.post(() -> BrutalPopup.toast(this,
                        "Failed to start service.", BrutalPopup.LENGTH_SHORT));
            }
        });
    }

    private void stopPlayback() {
        bg.execute(() -> {
            try {
                disableAccessibilityService();
                sendBroadcast(new Intent("intent.panpanpan.exit"));
            } catch (Throwable t) {
                Log.e(TAG, "stopPlayback", t);
            }
            ui.post(() -> {
                isServiceRunning = false;
                updateStartButton(healthMonitor.getLastStatus());
            });
        });
    }

    // ═══════════ FORCE CLOSE (NUCLEAR TEARDOWN) ═══════════

    /**
     * Nuclear teardown: kill EVERYTHING, clear session, exit to Login.
     *
     * SYNC ORDER (critical — overlay must be removed BEFORE process dies):
     *   1. Tell FloatingPanelService to remove overlay views immediately
     *   2. Disable AccessibilityService
     *   3. Kill daemon (closeAndExit via binder)
     *   4. Clear session + all active flags
     *   5. Stop FloatingPanelService
     *   6. Delay 500ms (let service onDestroy run) → kill process
     */
    private void performNuclearTeardown() {
        // Step 1: Remove overlay views IMMEDIATELY on main thread.
        // This ensures the floating panel disappears even if the service
        // hasn't processed stopService yet.
        try {
            sendBroadcast(new Intent("intent.panpanpan.force_remove_overlay")
                    .setPackage(getPackageName()));
        } catch (Throwable ignored) {}

        bg.execute(() -> {
            // Step 2: Disable accessibility service
            try { disableAccessibilityService(); } catch (Throwable ignored) {}

            // Step 3: Kill daemon
            try {
                IGamePad gp = GamePadBridge.gamePad;
                if (gp != null) gp.closeAndExit();
            } catch (Throwable ignored) {}
            try {
                sendBroadcast(new Intent("intent.panpanpan.exit"));
            } catch (Throwable ignored) {}
            // Fallback: closeAndExit() over the binder does nothing if the
            // binder is already dead. Without this, the daemon process
            // (spawned by Shizuku/root, outside our own process) can be
            // orphaned and keep running in the background indefinitely.
            DaemonControl.kill(this);

            // Step 4: Clear session
            sp.edit()
                    .remove("panel_user_dismissed")
                    .apply();
            GamePadBridge.gamePad = null;

            // Step 5: Stop floating panel service
            try { stopService(new Intent(this, FloatingPanelService.class)); }
            catch (Throwable ignored) {}

            // Step 6: Delay to let service onDestroy clean up, then kill
            ui.postDelayed(() -> {
                finishAffinity();
                android.os.Process.killProcess(android.os.Process.myPid());
            }, 500);
        });
    }

    // ═══════════ HELPER METHODS ═══════════

    private void enableAccessibilityService() {
        // See AccessibilityGate — plain "does the string already contain it"
        // isn't enough, since Android 13+ restricted settings can let that
        // write succeed while never actually binding the service.
        AccessibilityGate.ensureEnabled(this);
    }

    private void disableAccessibilityService() {
        String svcName = new ComponentName(getPackageName(),
                MidiBridgeService.class.getName()).flattenToString();
        String cur = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur != null && cur.contains(svcName)) {
            List<String> list = new ArrayList<>(Arrays.asList(cur.split(":")));
            list.remove(svcName);
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    TextUtils.join(":", list));
        }
    }

    private boolean canDrawOverlays() {
        try { return Settings.canDrawOverlays(this); }
        catch (Throwable t) { return false; }
    }

    private void showOverlayPermissionDialog() {
        BrutalPopup.dialog(this,
                "Overlay Permission",
                "Papiano needs permission to display over other apps so the floating panel stays visible while you play.",
                "Open Settings",
                () -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable t) {
                        BrutalPopup.toast(this,
                                "Open Settings manually: Apps > Papiano > Display over apps",
                                BrutalPopup.LENGTH_LONG);
                    }
                },
                "Cancel", null,
                null, null,
                true);
    }

    private void startFloatingPanel() {
        try {
            Intent svc = new Intent(this, FloatingPanelService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startFloatingPanel", t);
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return "Unknown";
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private void requestBatteryExemption() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                ui.post(() -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable ignored) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.app.NotificationManager nm = (android.app.NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && !nm.areNotificationsEnabled()) {
                    ui.post(() -> {
                        try {
                            requestPermissions(new String[]{
                                    "android.permission.POST_NOTIFICATIONS"}, 0);
                        } catch (Throwable ignored) {}
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private void unzipFiles() {
        java.io.File extDir = getExternalFilesDir(null);
        if (extDir == null) return;
        String base = extDir.getPath();

        try {
            InputStream is = getAssets().open("starter.sh");
            FileOutputStream fos = new FileOutputStream(base + "/starter.sh");
            byte[] buf = new byte[4096]; int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            is.close(); fos.close();
        } catch (Exception ignored) {}

        try {
            ZipFile zip = new ZipFile(getPackageResourcePath());
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("classes4.dex")) {
                    InputStream in = zip.getInputStream(entry);
                    FileOutputStream out = new FileOutputStream(base + "/GyroNative.dex");
                    byte[] buf = new byte[4096]; int l;
                    while ((l = in.read(buf)) > 0) out.write(buf, 0, l);
                    in.close(); out.close();
                    break;
                }
            }
            zip.close();
        } catch (Exception ignored) {}

        try {
            FileInputStream in = new FileInputStream(
                    getApplicationInfo().nativeLibraryDir + "/libpanpanpan.so");
            FileOutputStream out = new FileOutputStream(base + "/libpanpanpan.so");
            byte[] buf = new byte[4096]; int l;
            while ((l = in.read(buf)) > 0) out.write(buf, 0, l);
            in.close(); out.close();
        } catch (Exception ignored) {}
    }

    // ═══════════ LIFECYCLE ═══════════

    @Override
    protected void onResume() {
        super.onResume();
        healthMonitor.forceCheck();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        healthMonitor.destroy();
        if (isBroadcastRegistered) {
            try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
        }
        bg.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        // Just minimize, don't destroy session
        moveTaskToBack(true);
    }
}
