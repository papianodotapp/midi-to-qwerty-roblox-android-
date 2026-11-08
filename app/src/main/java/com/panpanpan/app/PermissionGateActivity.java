package com.panpanpan.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Permission Gate — atomic activation flow.
 *
 * From the user's perspective there is ONE operation: "Activate".
 * Internally it chains: ping source → grant perm → enable accessibility → spawn daemon → wait binder.
 * User sees a single status line that progresses through steps.
 *
 * If ANY step fails → show which step failed + actionable message.
 * ALL pass → auto-navigate to Home.
 */
public class PermissionGateActivity extends Activity {

    private static final String TAG = "PermGate";

    private TextView tvStatus, tvDetail;
    private ProgressBar progressBar;
    private Button btnActivate, btnContinue;
    private View cardRoot;

    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "activation-bg");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean activating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_gate);

        sp = getSharedPreferences("data", 0);

        tvStatus = findViewById(R.id.tv_gate_status);
        tvDetail = findViewById(R.id.tv_gate_detail);
        progressBar = findViewById(R.id.progress_gate);
        btnActivate = findViewById(R.id.btn_activate);
        btnContinue = findViewById(R.id.btn_continue);

        btnActivate.setOnClickListener(v -> startActivation());
        btnContinue.setOnClickListener(v -> navigateHome());

        // Auto-check: if already fully activated, skip straight to Home.
        //
        // Permission + accessibility state survives reboots, but the daemon
        // itself does NOT — it's a separate Shizuku/root-spawned process,
        // not an Android service, so a reboot (or Shizuku restarting) kills
        // it silently. Without a check here, reopening the app after that
        // skipped straight to a dead connection and left the user staring
        // at "Recovering..." for however long MainActivity's health-monitor
        // loop took to notice and retry. Kick a respawn immediately instead.
        bg.execute(() -> {
            if (isFullyActivated()) {
                if (!isDaemonAlive()) {
                    DaemonControl.respawn(getApplicationContext());
                }
                ui.post(this::navigateHome);
            }
        });
    }

    private boolean isDaemonAlive() {
        IGamePad gp = GamePadBridge.gamePad;
        if (gp == null) return false;
        try { return gp.asBinder().pingBinder(); } catch (Throwable t) { return false; }
    }

    // ═══════════ ATOMIC ACTIVATION ═══════════

    private void startActivation() {
        if (activating) return;
        activating = true;

        btnActivate.setEnabled(false);
        btnContinue.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);

        String method = sp.getString("activation_method", null);

        if (method == null) {
            // First time — ask user to choose
            showMethodChoice();
            return;
        }

        bg.execute(() -> runActivation(method));
    }

    private void showMethodChoice() {
        activating = false;
        btnActivate.setEnabled(true);
        progressBar.setVisibility(View.GONE);

        BrutalPopup.dialog(this,
                "Activation Method",
                "Choose how to activate Papiano:",
                "Root",
                () -> {
                    sp.edit().putString("activation_method", "root").apply();
                    startActivation();
                },
                "Shizuku",
                () -> {
                    sp.edit().putString("activation_method", "shizuku").apply();
                    startActivation();
                },
                null, null,
                true);
    }

    /**
     * How long to poll for the daemon actually connecting before giving up
     * and letting the user continue anyway (the background recovery loop
     * keeps trying regardless — see MidiBridgeService). Generous on purpose:
     * a fixed short guess is exactly what caused two different bugs here
     * before (see history below), so this polls instead of guessing once.
     */
    private static final long ACTIVATION_TIMEOUT_MS = 12_000L;
    private static final long ACTIVATION_POLL_INTERVAL_MS = 400L;

    /**
     * Activation — runs on bg thread, posts status updates to UI.
     *
     * Used to be an "atomic" gate: grant permission, sleep(300), verify;
     * enable accessibility, sleep(500), verify; spawn daemon, sleep(1000);
     * only THEN declare success. Those sleep-then-verify steps guessed a
     * fixed propagation delay — if a slower device took a bit longer than
     * 300/500ms, the gate declared FAILURE even though the step would have
     * succeeded moments later, sending the user back to square one for no
     * real reason.
     *
     * That was "fixed" by removing the verification entirely — grant
     * permission, enable accessibility, spawn daemon, sleep(500) for UX
     * pacing only, then declare "Activated!" UNCONDITIONALLY. That traded
     * one bug for a worse one: the gate now says "Activated!" even when
     * NONE of the previous steps actually worked (Shizuku permission never
     * granted, daemon never spawned, accessibility never bound) — the only
     * hard check left was "is Shizuku/root reachable at all", which says
     * nothing about whether activation actually succeeded.
     *
     * Fixed properly this time: poll for the daemon ACTUALLY connecting,
     * with a generous timeout instead of a single guessed-delay snapshot.
     * If it connects, great — same "Activated!" screen as before. If it's
     * still not connected after a generous wait, say so honestly instead of
     * lying, but still let the user continue to Home (where the persistent
     * recovery loop keeps retrying regardless of this screen).
     */
    private void runActivation(String method) {
        try {
            // ── Unzip daemon files (starter.sh, .dex, .so) ──
            // Must happen BEFORE spawning daemon — otherwise starter.sh doesn't exist.
            postStatus("Preparing files...", "");
            unzipFiles();

            // ── Ping activation source — the one real, instant check ──
            postStatus("Checking " + method + "...", "");

            boolean sourceAlive = pingSource(method);
            if (!sourceAlive) {
                postFailed(capitalize(method) + " is not running.",
                        method.equals("shizuku")
                                ? "Open Shizuku app and make sure it's active."
                                : "Root access not available.");
                return;
            }

            // ── Grant permission ──
            // Unlike the checks removed in the previous fix (which guessed a
            // fixed propagation delay after an already-completed action),
            // this is a genuine yes/no answer for whether the Shizuku
            // permission dialog was actually granted — worth hard-failing on,
            // since nothing downstream can work without it.
            postStatus("Activating...", "");

            if (!grantPermission(method)) {
                postFailed("Permission not granted.",
                        method.equals("shizuku")
                                ? "Shizuku permission was denied or timed out. Tap Retry and allow the popup."
                                : "Root access denied.");
                return;
            }

            // ── Enable accessibility, spawn daemon ──
            // Confirmed on a real device: the OFF->ON cycle AccessibilityGate
            // forces doesn't always land on the very first try — give it a
            // real chance to actually bind, and retry the cycle once if it
            // didn't, before bothering to spawn the daemon (which is pointless
            // if MidiBridgeService, the thing that would catch its binder,
            // isn't even alive yet).
            enableAccessibility();
            if (!waitForAccessibilityBound(ACCESSIBILITY_BIND_TIMEOUT_MS)) {
                enableAccessibility();
                waitForAccessibilityBound(ACCESSIBILITY_BIND_TIMEOUT_MS);
            }
            DaemonControl.respawn(getApplicationContext());

            // ── Poll for the daemon actually connecting ──
            postStatus("Connecting to daemon...", "");
            if (waitForDaemon(ACTIVATION_TIMEOUT_MS)) {
                postSuccess();
            } else {
                postPending();
            }

        } catch (InterruptedException e) {
            postFailed("Activation interrupted.", "");
        } catch (Throwable t) {
            Log.e(TAG, "runActivation", t);
            postFailed("Unexpected error: " + t.getMessage(), "");
        } finally {
            activating = false;
        }
    }

    private boolean waitForDaemon(long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isDaemonAlive()) return true;
            Thread.sleep(ACTIVATION_POLL_INTERVAL_MS);
        }
        return isDaemonAlive();
    }

    private static final long ACCESSIBILITY_BIND_TIMEOUT_MS = 5_000L;

    private boolean waitForAccessibilityBound(long timeoutMs) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isAccessibilityEnabled()) return true;
            Thread.sleep(ACTIVATION_POLL_INTERVAL_MS);
        }
        return isAccessibilityEnabled();
    }

    // ═══════════ SUB-STEPS ═══════════

    private boolean pingSource(String method) {
        switch (method) {
            case "shizuku":
                try {
                    return rikka.shizuku.Shizuku.pingBinder();
                } catch (Throwable t) {
                    return false;
                }
            case "root":
                try {
                    Process p = Runtime.getRuntime().exec("su -c id");
                    int exit = p.waitFor();
                    return exit == 0;
                } catch (Throwable t) {
                    return false;
                }
            default:
                return false;
        }
    }

    /** How long to wait for a human to notice and tap the Shizuku permission
     * popup. Generous on purpose — this is waiting on a PERSON, not a system
     * state propagation delay, so there's no "correct" short guess here. */
    private static final long SHIZUKU_PERMISSION_TIMEOUT_MS = 60_000L;

    private boolean grantPermission(String method) {
        String cmd = "pm grant " + getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS";
        try {
            switch (method) {
                case "shizuku": {
                    if (rikka.shizuku.Shizuku.checkSelfPermission()
                            != PackageManager.PERMISSION_GRANTED) {
                        if (!requestShizukuPermissionAndWait()) {
                            return false;
                        }
                    }
                    Process p = rikka.shizuku.Shizuku.newProcess(
                            new String[]{"sh", "-c", cmd}, null, null);
                    p.waitFor();
                    return true;
                }
                case "root": {
                    Process p = Runtime.getRuntime().exec("su");
                    OutputStream o = p.getOutputStream();
                    o.write((cmd + "\nexit\n").getBytes());
                    o.flush();
                    o.close();
                    p.waitFor();
                    return true;
                }
                default:
                    return false;
            }
        } catch (Throwable t) {
            Log.e(TAG, "grantPermission", t);
            return false;
        }
    }

    /**
     * Shizuku's permission grant is a REAL SYSTEM DIALOG — a human needs
     * time to notice it and tap Allow. The previous version of this code
     * called Shizuku.requestPermission(0), blindly slept 2 seconds (nowhere
     * near enough time for a person to react), and then proceeded regardless
     * of what the user actually did — the result was never even checked.
     * That's the same class of bug fixed elsewhere in this flow (guessing a
     * fixed delay instead of waiting for the real event), but for the most
     * foundational step: without this permission, EVERYTHING downstream
     * (WRITE_SECURE_SETTINGS grant, accessibility enable, daemon spawn) is
     * doomed, silently.
     *
     * This waits for Shizuku's actual OnRequestPermissionResultListener
     * callback instead — exactly what the app's original pre-rebuild version
     * did — with a generous timeout as a safety net only (not a guess at how
     * long the user needs; genuinely waiting for their response).
     */
    private boolean requestShizukuPermissionAndWait() {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] granted = {false};
        final rikka.shizuku.Shizuku.OnRequestPermissionResultListener listener =
                (requestCode, grantResult) -> {
                    granted[0] = grantResult == PackageManager.PERMISSION_GRANTED;
                    latch.countDown();
                };
        rikka.shizuku.Shizuku.addRequestPermissionResultListener(listener);
        try {
            postStatus("Waiting for Shizuku permission...",
                    "Check for a system popup and tap Allow.");
            ui.post(() -> rikka.shizuku.Shizuku.requestPermission(0));
            boolean fired = latch.await(
                    SHIZUKU_PERMISSION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            return fired && granted[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(listener);
        }
    }

    private void enableAccessibility() {
        AccessibilityGate.ensureEnabled(this);
    }

    // ═══════════ STATE CHECKS ═══════════

    private boolean hasWriteSecureSettings() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks the REAL bound state, not just whether Settings.Secure lists our
     * service — see AccessibilityGate for why that distinction matters
     * (Android 13+ restricted settings can let the string-write succeed while
     * silently blocking the actual bind).
     */
    private boolean isAccessibilityEnabled() {
        return AccessibilityGate.isActuallyBound(this);
    }

    /** Activation = permission + accessibility. Daemon is async. */
    private boolean isFullyActivated() {
        if (!hasWriteSecureSettings()) return false;
        if (!isAccessibilityEnabled()) return false;
        return true;
    }

    // ═══════════ UI HELPERS ═══════════

    private void postStatus(String status, String detail) {
        ui.post(() -> {
            tvStatus.setText(status);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            tvDetail.setText(detail);
            tvDetail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
            progressBar.setVisibility(View.VISIBLE);
            btnActivate.setEnabled(false);
        });
    }

    private void postFailed(String status, String detail) {
        ui.post(() -> {
            tvStatus.setText(status);
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
            tvDetail.setText(detail);
            tvDetail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
            progressBar.setVisibility(View.GONE);
            btnActivate.setEnabled(true);
            btnActivate.setText("Retry");
            btnContinue.setVisibility(View.GONE);
        });
    }

    private void postSuccess() {
        ui.post(() -> {
            tvStatus.setText("Activated!");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
            tvDetail.setText("All systems ready. Tap Continue.");
            tvDetail.setVisibility(View.VISIBLE);
            tvDetail.setTextColor(ContextCompat.getColor(this, R.color.green));
            progressBar.setVisibility(View.GONE);
            btnActivate.setVisibility(View.GONE);
            btnContinue.setVisibility(View.VISIBLE);
        });
    }

    /**
     * Daemon didn't connect within ACTIVATION_TIMEOUT_MS. Say so honestly
     * instead of declaring "Activated!" — but still let the user continue,
     * since MidiBridgeService's own recovery loop keeps retrying in the
     * background regardless of what this screen shows.
     */
    private void postPending() {
        ui.post(() -> {
            tvStatus.setText("Still connecting...");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_gold));
            tvDetail.setText("Taking longer than usual on this device — it'll keep " +
                    "trying in the background. Check the Home screen for live status.");
            tvDetail.setVisibility(View.VISIBLE);
            tvDetail.setTextColor(ContextCompat.getColor(this, R.color.accent_gold));
            progressBar.setVisibility(View.GONE);
            btnActivate.setVisibility(View.GONE);
            btnContinue.setVisibility(View.VISIBLE);
        });
    }

    private void navigateHome() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // ═══════════ UNZIP DAEMON FILES ═══════════

    /**
     * Extract starter.sh, GyroNative.dex, libpanpanpan.so to external files dir.
     * These are required for the daemon to start. Must run before DaemonControl.respawn().
     */
    private void unzipFiles() {
        java.io.File extDir = getExternalFilesDir(null);
        if (extDir == null) return;
        String base = extDir.getPath();

        try {
            java.io.InputStream is = getAssets().open("starter.sh");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(base + "/starter.sh");
            byte[] buf = new byte[4096]; int len;
            while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
            is.close(); fos.close();
        } catch (Exception ignored) {}

        try {
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(getPackageResourcePath());
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("classes4.dex")) {
                    java.io.InputStream in = zip.getInputStream(entry);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(base + "/GyroNative.dex");
                    byte[] buf = new byte[4096]; int l;
                    while ((l = in.read(buf)) > 0) out.write(buf, 0, l);
                    in.close(); out.close();
                    break;
                }
            }
            zip.close();
        } catch (Exception ignored) {}

        try {
            java.io.FileInputStream in = new java.io.FileInputStream(
                    getApplicationInfo().nativeLibraryDir + "/libpanpanpan.so");
            java.io.FileOutputStream out = new java.io.FileOutputStream(base + "/libpanpanpan.so");
            byte[] buf = new byte[4096]; int l;
            while ((l = in.read(buf)) > 0) out.write(buf, 0, l);
            in.close(); out.close();
        } catch (Exception ignored) {}
    }

    // ═══════════ LIFECYCLE ═══════════

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        // This is the entry point now (no login gate) — don't allow back.
        finishAffinity();
    }
}
