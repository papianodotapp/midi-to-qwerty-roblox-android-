package com.panpanpan.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single place that shells out to the Shizuku/root-spawned daemon process.
 *
 * Before this, respawn logic was duplicated in MidiBridgeService (bridge health
 * loop, tied to the AccessibilityService) AND PermissionHealthMonitor (tied to
 * MainActivity). Both loops could fire respawnDaemon() around the same time
 * with no shared state, stacking multiple app_process/uHID daemon instances
 * on top of each other — a likely cause of intermittent "can't connect".
 * Centralizing here gives both loops one cooldown + in-flight guard.
 */
object DaemonControl {
    // Same tag as MidiBridgeService — anyone already filtering logcat for
    // "Papiano" (e.g. MatLog) picks these up automatically, no extra setup.
    private const val TAG = "PapianoMidi"
    private const val RESPAWN_COOLDOWN_MS = 8_000L
    private const val DAEMON_CLASS = "com.panpanpan.app.GamePadNative"

    private val respawnInFlight = AtomicBoolean(false)
    @Volatile private var lastRespawnAttemptMs = 0L

    private fun method(context: Context): String =
        context.getSharedPreferences("data", 0)
            .getString("activation_method", "shizuku") ?: "shizuku"

    private fun starterCmd(context: Context): String {
        val extDir = context.getExternalFilesDir(null)
        val base = extDir?.path ?: context.filesDir.path
        return "sh $base/starter.sh"
    }

    /**
     * Fire-and-forget daemon (re)spawn, rate-limited so the service's health
     * loop and the activity's health monitor never stack duplicate daemons.
     * Safe to call as often as callers like — extra calls within the
     * cooldown window are dropped.
     */
    @JvmStatic
    fun respawn(context: Context) {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastRespawnAttemptMs < RESPAWN_COOLDOWN_MS) return
            if (!respawnInFlight.compareAndSet(false, true)) return
            lastRespawnAttemptMs = now
        }
        val appContext = context.applicationContext
        val m = method(appContext)
        val cmd = starterCmd(appContext)
        Thread {
            try {
                // starter.sh captures the daemon's own stdout/stderr (which
                // used to vanish into /dev/null — invisible even to a full
                // logcat capture if app_process crashed on startup) and cats
                // it back into ITS OWN stdout before exiting, so it's included
                // in whatever runShell() captures here. One log line now shows
                // exactly why a respawn attempt did or didn't work.
                val shellOutput = runShell(m, cmd)
                Log.w(TAG, "respawn attempt via $m — output: " +
                        "[${shellOutput.ifBlank { "<empty>" }}]")
            } catch (t: Throwable) {
                Log.e(TAG, "respawn", t)
            } finally {
                respawnInFlight.set(false)
            }
        }.start()
    }

    /**
     * Force-kill the daemon process even if its binder is already dead.
     * closeAndExit() over the binder can't help once the binder itself is
     * gone — without this fallback, the orphaned app_process (spawned by
     * Shizuku/root, outside the app's own process) keeps running forever,
     * holding the virtual keyboard open, until the device reboots.
     */
    @JvmStatic
    fun kill(context: Context) {
        val appContext = context.applicationContext
        val m = method(appContext)
        Thread {
            try {
                val out = runShell(m, "pkill -f $DAEMON_CLASS")
                if (out.isNotBlank()) Log.w(TAG, "kill output: $out")
            } catch (t: Throwable) {
                Log.e(TAG, "kill", t)
            }
        }.start()
    }

    private fun readAll(stream: InputStream): String = try {
        stream.bufferedReader().readText()
    } catch (t: Throwable) {
        ""
    }

    /**
     * MUST run off the main thread — blocks on Process.waitFor(). Returns
     * everything the shell invocation printed to stdout/stderr: "pm grant" /
     * "pm path" failures, "No such file", permission denials, AND (thanks to
     * starter.sh catting it back before exiting) whatever the backgrounded
     * daemon itself printed in its first second — the actual crash reason if
     * app_process died on startup, which used to be thrown away entirely.
     */
    private fun runShell(method: String, cmd: String): String {
        return when (method) {
            "root" -> {
                val p = Runtime.getRuntime().exec("su")
                p.outputStream.use {
                    it.write("$cmd\nexit\n".toByteArray())
                    it.flush()
                }
                p.waitFor()
                readAll(p.inputStream) + readAll(p.errorStream)
            }
            "shizuku" -> {
                if (rikka.shizuku.Shizuku.checkSelfPermission()
                        != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) return "shizuku permission not granted"
                val p = rikka.shizuku.Shizuku.newProcess(arrayOf("sh"), null, null)
                p.outputStream.use {
                    it.write("$cmd\nexit\n".toByteArray())
                    it.flush()
                }
                p.waitFor()
                val out = readAll(p.inputStream) + readAll(p.errorStream)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    p.destroyForcibly()
                } else {
                    @Suppress("DEPRECATION") p.destroy()
                }
                out
            }
            // "adb": user activated manually via adb shell, no channel to re-trigger.
            else -> ""
        }
    }
}
