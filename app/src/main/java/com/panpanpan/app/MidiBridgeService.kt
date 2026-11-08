package com.panpanpan.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background-only MIDI -> virtual keyboard bridge. No popup overlay, no UI.
 *
 * QWERTY only. Octave range covers the full 88-key span (21..108 = A0..C8).
 * MIDI device name is broadcast to MainActivity via "intent.panpanpan.midi_device".
 *
 * STABILITY NOTES (POCO X3 Pro / MIUI 14 bug-report driven):
 * Bug report user nunjukin system_server kena watchdog kill 3x karena deadlock
 * di UsbHostManager (UsbDirectMidiDevice.close <-> openDevice). Kalau kita
 * panggil MidiManager.devices / openDevice / registerDeviceCallback SYNC di
 * main thread service, kita bakal ANR ke deadlock yang sama.
 *
 * Fix: semua MIDI binder call dipindah ke HandlerThread sendiri ("midi-thread")
 * dan di-defer 1.5 detik setelah onServiceConnected supaya system_server sempat
 * settle dari accessibility binding. Callback dari MidiManager juga dikirim
 * lewat handler thread itu (bukan main), supaya kalau hang gak nge-block UI.
 */
class MidiBridgeService : AccessibilityService() {

    private var iGamePad: IGamePad? = null
    private var isBroadcastRegistered = false
    private var isGamePadCreated = false
    private var sp: SharedPreferences? = null

    /**
     * Velocity ("Visual Piano") toggle. Saat true, velocity not dikirim ke
     * native (yang akan tap Alt+velKey skema 32-step sebelum not). Saat false,
     * kita kirim velocity=0 -> native main QWERTY polos. State disimpan di
     * pref "velocity_enabled" dan di-update live via broadcast
     * "intent.panpanpan.set_velocity". Hot-path (per-not), jadi @Volatile field
     * dibaca langsung tanpa nyentuh SharedPreferences tiap not.
     */
    @Volatile
    private var velocityEnabled = false

    private var midiManager: MidiManager? = null
    private var deviceCallback: MidiManager.DeviceCallback? = null
    private var midiOutputPort: MidiOutputPort? = null

    /**
     * MidiDevice yg lagi kita pegang. DULU gak disimpen sama sekali — cuma
     * port-nya yg ditutup. Akibatnya tiap reconnect / rescan ninggalin
     * MidiDevice yg masih kebuka di system_server, lalu ditutup "random" sama
     * finalizer GC di thread lain. Close dari finalizer yg barengan sama
     * openDevice baru itu persis pola deadlock UsbDirectMidiDevice
     * (close <-> open) di system_server yg bikin HP freeze total sampe
     * harus force reboot. Sekarang device SELALU ditutup eksplisit,
     * berpasangan sama port-nya, di midi-thread.
     */
    private var midiDevice: MidiDevice? = null

    /** True setelah onDestroy — callback openDevice yg telat langsung close device. */
    @Volatile
    private var destroyed = false

    /**
     * Backoff kalau openDevice gagal: jangan hajar system_server dgn
     * open/close berulang tiap rescan (itu yg memicu deadlock USB MIDI).
     */
    private var openFailures = 0
    private var nextOpenAllowedAt = 0L

    /**
     * ID device MIDI yg lagi kita pegang port-nya. -1 = belum ada. Dipake
     * supaya periodic rescan tau "device baru" vs "device yg sama" -> gak
     * spam open ulang port tiap 3 detik.
     */
    private var currentMidiDeviceId: Int = -1
    private var lastBroadcastName: String = ""

    /**
     * True saat kita lagi tunggu callback dari MidiManager.openDevice.
     * Tanpa flag ini, rescan periodik tiap 3s bisa nge-trigger openDevice
     * kedua kalinya kalau callback pertama lambat (USB MIDI di MIUI sering
     * pelan), nyebabin port double-open + popup spam Connected/Disconnected.
     */
    private var isOpeningMidi: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Binder yg sedang kita pegang. Disimpan supaya bisa unlinkToDeath
     * saat ganti binder baru atau cleanup.
     */
    private var currentBinder: android.os.IBinder? = null

    /**
     * DeathRecipient: dipanggil oleh system INSTANT saat proses daemon mati.
     * Ini solusi utama buat masalah "MIDI file player masih jalan tapi QWERTY
     * gak nyala" — sebelumnya gak ada mekanisme detect binder death mid-session.
     *
     * Flow: daemon mati → binderDied() → cleanup iGamePad → auto-respawn →
     * daemon baru kirim binder via broadcast → handleBinder() re-attach.
     *
     * Cuma fire SATU respawn attempt langsung (buat respons cepet). Kalau itu
     * gagal, bridgeHealthRunnable (jalan tiap BRIDGE_HEALTH_INTERVAL selama
     * service hidup) yang lanjutin nyoba — gak perlu cascade retry terpisah
     * di sini, satu periodic loop itu udah cukup dan gak akan nyerah.
     */
    private val binderDeathRecipient = android.os.IBinder.DeathRecipient {
        Log.w(TAG, "!!! BINDER DIED: daemon process killed mid-session. " +
                "Auto-respawning...")

        // Cleanup state di main thread supaya thread-safe dgn broadcast receiver
        mainHandler.post {
            iGamePad = null
            GamePadBridge.gamePad = null
            isGamePadCreated = false
            currentBinder = null
            warnedNullBridge.set(false)
            respawnDaemon()
        }
    }

    /**
     * Periodic bridge health check — the ONE recovery loop for this service.
     * Jalan TERUS selama service hidup (bukan cuma sekali di onServiceConnected),
     * tiap BRIDGE_HEALTH_INTERVAL. Covers every case:
     *   - iGamePad null (belum pernah connect, atau respawn sebelumnya gagal)
     *     -> keep retrying, gak pernah nyerah selama service ini hidup.
     *   - iGamePad non-null tapi binder-nya dead (DeathRecipient miss — rare,
     *     tapi possible kalau binder proxy udah di-GC sebelum daemon mati, atau
     *     daemon hang tanpa crash) -> cleanup + respawn.
     */
    private val bridgeHealthRunnable: Runnable = object : Runnable {
        override fun run() {
            try { checkBridgeHealth() } catch (e: Throwable) {
                Log.e(TAG, "bridgeHealth", e)
            } finally {
                mainHandler.postDelayed(this, BRIDGE_HEALTH_INTERVAL)
            }
        }
    }

    private fun checkBridgeHealth() {
        val gp = iGamePad
        if (gp == null) {
            respawnDaemon()
            return
        }
        val binder = currentBinder
        if (binder == null || !binder.pingBinder()) {
            Log.w(TAG, "Bridge health check: binder dead/unreachable. Cleaning up + respawn.")
            // Binder sudah mati tapi DeathRecipient gak ke-trigger (rare case)
            iGamePad = null
            GamePadBridge.gamePad = null
            isGamePadCreated = false
            try { binder?.unlinkToDeath(binderDeathRecipient, 0) } catch (_: Throwable) {}
            currentBinder = null
            warnedNullBridge.set(false)
            respawnDaemon()
        }
    }

    /** Worker thread khusus buat semua MIDI binder ops. Dilahirin di onServiceConnected. */
    private var midiThread: HandlerThread? = null
    private var midiHandler: Handler? = null

    /**
     * Periodic rescan. Fallback kalau MidiManager.DeviceCallback miss event.
     */
    private val rescanRunnable: Runnable = object : Runnable {
        override fun run() {
            try { rescanMidi() } catch (e: Throwable) { Log.e(TAG, "rescan", e) }
            finally {
                // Adaptive interval: fast saat connected, slow saat idle
                // Ini cuma FALLBACK — DeviceCallback udah nanganin plug/unplug
                // secara instan. Tiap mm.devices = binder call ke MidiService
                // di system_server (service yg sama yg rawan deadlock USB),
                // jadi jangan terlalu sering.
                val interval = if (currentMidiDeviceId != -1) 10_000L else 15_000L
                midiHandler?.postDelayed(this, interval)
            }
        }
    }

    private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    "intent.panpanpan.exit" -> {
                        try { disableSelf() } catch (e: Throwable) { Log.w(TAG, "disableSelf", e) }
                    }
                    "intent.panpanpan.send_binder" -> handleBinder(intent)
                    "intent.panpanpan.midi_request" -> {
                        // Floating panel minta snapshot state. Force re-broadcast
                        // dgn bypass dedup biar UI dapet info terbaru meskipun
                        // device-nya gak berubah sejak last broadcast.
                        midiHandler?.post {
                            val current = lastBroadcastName
                            lastBroadcastName = "\u0000force-resend"
                            broadcastMidiDevice(current)
                        }
                    }
                    "intent.panpanpan.set_velocity" -> {
                        // Toggle velocity dari UI (home / popup). Update field
                        // hot-path + persist biar konsisten lintas komponen.
                        val enabled = intent.getBooleanExtra("enabled", false)
                        velocityEnabled = enabled
                        GamePadBridge.velocityEnabled = enabled
                        try { sp?.edit()?.putBoolean("velocity_enabled", enabled)?.apply() }
                        catch (_: Throwable) {}
                        Log.d(TAG, "velocityEnabled = $enabled")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Service receiver onReceive error", t)
            }
        }
    }

    private fun handleBinder(intent: Intent) {
        val raw = extractBinder(intent)
        if (raw == null) {
            Log.w(TAG, "handleBinder: extractBinder returned null")
            return
        }
        if (!raw.pingBinder()) {
            Log.w(TAG, "handleBinder: binder dead (ping failed)")
            return
        }
        val binder: android.os.IBinder = raw

        // Unlink DeathRecipient dari binder LAMA (kalau ada) sebelum attach yg baru.
        // Tanpa ini, kalau daemon respawn dan kirim binder baru, kita masih listen
        // ke binder lama yg udah dead → leak + false trigger.
        try { currentBinder?.unlinkToDeath(binderDeathRecipient, 0) } catch (_: Throwable) {}

        // Register DeathRecipient ke binder BARU. Ini yang detect instant
        // kalau daemon mati di tengah playback → auto-respawn.
        try {
            binder.linkToDeath(binderDeathRecipient, 0)
            currentBinder = binder
            Log.d(TAG, "DeathRecipient linked to new daemon binder")
        } catch (e: RemoteException) {
            // linkToDeath throws RemoteException kalau binder UDAH dead
            // saat kita coba link. Ini berarti daemon mati antara extractBinder
            // dan sekarang — langsung respawn.
            Log.e(TAG, "linkToDeath failed (already dead), respawning...", e)
            currentBinder = null
            respawnDaemon()
            return
        }

        iGamePad = IGamePad.Stub.asInterface(binder)
        try {
            isGamePadCreated = iGamePad?.create() == true
        } catch (e: RemoteException) {
            Log.e(TAG, "create", e)
        }

        if (isGamePadCreated) {
            Log.d(TAG, "Virtual HID ready, MIDI bridge live")
            warnedNullBridge.set(false)
            DaemonControl.onConnected()

            // Publish live binder for direct (no-broadcast) note delivery.
            GamePadBridge.gamePad = iGamePad
            GamePadBridge.velocityEnabled = velocityEnabled
        } else {
            Log.e(TAG, "Virtual HID create FAILED (uHID open needs root/shell perm)")
            try { iGamePad?.closeAndExit() } catch (_: RemoteException) {}
            iGamePad = null
            GamePadBridge.gamePad = null
        }
    }

    /**
     * Show toast dari thread mana pun (kebanyakan call dari midiHandler thread).
     * Popup wajib di main thread; kalau enggak, IllegalStateException.
     */
    private fun toastOnMain(msg: String) {
        mainHandler.post {
            try { BrutalPopup.toast(this, msg, BrutalPopup.LENGTH_LONG) }
            catch (t: Throwable) { Log.w(TAG, "toast", t) }
        }
    }

    /**
     * One-shot warning flag: kalau iGamePad null saat user pencet MIDI key,
     * kita kasih toast + log SEKALI, bukan tiap note (biar gak spam toast).
     * Reset ke false pas binder berhasil terhubung.
     */
    private val warnedNullBridge = AtomicBoolean(false)

    private fun extractBinder(intent: Intent): android.os.IBinder? = try {
        when (val raw = intent.getParcelableExtra<android.os.Parcelable>("binder")) {
            is BinderContainer -> raw.binder
            else -> null
        }
    } catch (t: Throwable) {
        Log.w(TAG, "extractBinder failed: ${t.javaClass.simpleName}")
        try { sendBroadcast(Intent("intent.panpanpan.exit")) } catch (_: Throwable) {}
        null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        sp = getSharedPreferences("data", 0)
        // Restore velocity toggle state (default OFF).
        velocityEnabled = sp?.getBoolean("velocity_enabled", false) ?: false
        GamePadBridge.velocityEnabled = velocityEnabled

        // Start MIDI worker thread DULU sebelum apa pun yg mungkin nge-block.
        if (midiThread == null) {
            midiThread = HandlerThread("midi-thread").also { it.start() }
            midiHandler = Handler(midiThread!!.looper)
        }

        try {
            ContextCompat.registerReceiver(this, mBroadcastReceiver,
                IntentFilter("intent.panpanpan.send_binder"),
                ContextCompat.RECEIVER_EXPORTED)
            val internalFilter = IntentFilter().apply {
                addAction("intent.panpanpan.exit")
                addAction("intent.panpanpan.midi_request")
                addAction("intent.panpanpan.set_velocity")
            }
            ContextCompat.registerReceiver(this, mBroadcastReceiver,
                internalFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED)
            isBroadcastRegistered = true
        } catch (e: Throwable) { Log.e(TAG, "registerReceiver", e) }

        // sendNotification harus jalan di sini biar service jadi foreground (Android 14
        // butuh ini segera setelah accessibility connect, kalau enggak system_server
        // bisa kill service).
        try { sendNotification() } catch (e: Throwable) { Log.e(TAG, "sendNotification", e) }

        // NOTE: dulu di sini ada AccessibilityGate.ensureEnabled(). Itu bisa
        // nge-toggle accessibility OFF->ON dari DALAM service yg lagi jalan
        // (kalau AccessibilityManager telat update) -> service ke-unbind,
        // ke-bind lagi, onServiceConnected lagi, toggle lagi... loop rebind
        // yg tiap putaran juga buka/tutup MIDI device. Kalau service ini
        // jalan, artinya udah ke-bind — gak perlu dicek dari sini.

        // Defer initMidi 1500ms. Berdasarkan bug report MIUI 14, system_server
        // bisa lagi recovering dari UsbHostManager deadlock pas user buka app.
        // Kasih waktu settle dulu sebelum binder MidiManager. Setelah init,
        // mulai polling rescan tiap 3s.
        midiHandler?.postDelayed({
            try { initMidi() } catch (e: Throwable) { Log.e(TAG, "initMidi", e) }
            midiHandler?.postDelayed(rescanRunnable, 3000L)
        }, 1500L)

        // Start the bridge health check loop — first run 6s after connect
        // (enough time for the daemon's sticky broadcast to arrive on a normal
        // start; if iGamePad is still null at that point it auto-respawns),
        // then it keeps rescheduling itself every BRIDGE_HEALTH_INTERVAL for as
        // long as the service is alive. One loop covers both "never connected"
        // and "connected then died" — no separate cascade of one-shot timers.
        mainHandler.postDelayed(bridgeHealthRunnable, 6000L)

        // Floating panel auto-on: kalau user udah granting SYSTEM_ALERT_WINDOW
        // DAN belum dismiss panel via tombol ✕, hidupin panel pas accessibility
        // nyala. Hormati user_dismissed flag biar gak nge-restart panel yg
        // baru aja user tutup.
        try {
            val dismissed = sp?.getBoolean(
                FloatingPanelService.KEY_USER_DISMISSED, false) ?: false
            if (Settings.canDrawOverlays(this) && !dismissed) {
                val svcIntent = Intent(this, FloatingPanelService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
            }
        } catch (e: Throwable) { Log.w(TAG, "startFloatingPanel", e) }
    }

    /**
     * Re-spawn daemon process via Shizuku atau root (sesuai activation_method
     * yg user pilih waktu Activate). Dipanggil dari binderDeathRecipient
     * (respons cepet pas daemon mati mid-session) dan dari checkBridgeHealth
     * (retry berkelanjutan selama iGamePad masih null). Ini SATU-SATUNYA
     * tempat yang memicu respawn di seluruh app — PermissionHealthMonitor
     * (punya MainActivity) cuma observer, gak lagi ikut memicu respawn sendiri.
     *
     * Delegated ke DaemonControl buat cooldown/in-flight guard, jaga-jaga
     * kalau ada dua trigger (mis. binder death + periodic check) nyaris
     * bersamaan.
     */
    private fun respawnDaemon() {
        DaemonControl.respawn(this)
    }

    /* ---------------- MIDI (semua di midiHandler thread) ---------------- */

    /** MUST run on midiHandler thread. */
    private fun initMidi() {
        if (midiManager == null) {
            midiManager = try {
                getSystemService(MIDI_SERVICE) as? MidiManager
            } catch (e: Throwable) {
                Log.e(TAG, "getSystemService(MIDI)", e); null
            }
        }
        val mm = midiManager ?: run {
            Log.w(TAG, "MidiManager unavailable, MIDI disabled")
            return
        }

        deviceCallback = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) {
                Log.d(TAG, "callback onDeviceAdded id=${info.id}")
                midiHandler?.post {
                    try { rescanMidi() } catch (e: Throwable) {
                        Log.e(TAG, "onDeviceAdded rescan", e)
                    }
                }
            }
            override fun onDeviceRemoved(info: MidiDeviceInfo) {
                Log.d(TAG, "callback onDeviceRemoved id=${info.id}")
                midiHandler?.post {
                    if (info.id == currentMidiDeviceId) {
                        closeMidi()
                        // Kalau open-nya in-flight pas device dicabut, jgn
                        // tinggalin flag stuck.
                        isOpeningMidi = false
                        openFailures = 0
                        nextOpenAllowedAt = 0L
                        broadcastMidiDevice("")
                    }
                }
            }
        }
        try {
            mm.registerDeviceCallback(deviceCallback!!, midiHandler)
        } catch (e: Throwable) {
            Log.e(TAG, "registerDeviceCallback", e)
        }

        // Initial scan (callback gak fire utk device yg udah attached SEBELUM
        // registerDeviceCallback dipanggil).
        rescanMidi()
    }

    /**
     * Cek device list aktual + reconcile dengan state kita. Aman dipanggil
     * berulang — kalau device yg lagi kita pegang masih ada, no-op. Kalau
     * device baru -> open. Kalau device kita ilang -> cleanup + broadcast.
     *
     * MUST run on midiHandler thread.
     */
    private fun rescanMidi() {
        val mm = midiManager ?: return
        if (isOpeningMidi) {
            // Open lain lagi in-flight; jangan tumpuk.
            return
        }
        val devices: List<MidiDeviceInfo> = try {
            mm.devices?.toList() ?: emptyList()
        } catch (e: Throwable) {
            Log.e(TAG, "mm.devices throw (deadlock?)", e)
            return
        }

        // Only log when device count changes — no spam tiap 3/10s.
        val outputCapable = devices.filter { it.outputPortCount > 0 }

        if (outputCapable.isEmpty()) {
            // Gak ada device output-capable. Kalau sebelumnya kita pegang
            // sesuatu, cleanup + kasih tau popup.
            if (currentMidiDeviceId != -1 || lastBroadcastName.isNotEmpty()) {
                closeMidi()
                broadcastMidiDevice("")
            }
            openFailures = 0
            nextOpenAllowedAt = 0L
            return
        }

        // Stabilkan pilihan device: kalau device current masih ada di list,
        // tetap pake itu (jangan flap ke device lain meskipun urutan
        // mm.devices berubah). Baru fallback ke first.
        val target = outputCapable.firstOrNull { it.id == currentMidiDeviceId }
            ?: outputCapable.first()

        if (target.id == currentMidiDeviceId && midiOutputPort != null) {
            return
        }

        // Backoff setelah open gagal (2s, 4s, 8s ... max 60s).
        if (SystemClock.elapsedRealtime() < nextOpenAllowedAt) return

        Log.d(TAG, "MIDI switching device: $currentMidiDeviceId -> ${target.id}")
        try { handleDevice(target) } catch (e: Throwable) {
            Log.e(TAG, "handleDevice from rescan", e)
        }
    }

    /**
     * Lepas semua key yg ke-hold, tutup port DAN device. MUST run on
     * midiHandler thread.
     */
    private fun closeMidi() {
        releaseAllKeys()
        try { midiOutputPort?.close() } catch (_: Throwable) {}
        midiOutputPort = null
        try { midiDevice?.close() } catch (_: Throwable) {}
        midiDevice = null
        currentMidiDeviceId = -1
    }

    /** All-notes-off di virtual keyboard (anti stuck key). */
    private fun releaseAllKeys() {
        try { iGamePad?.releaseAll() } catch (_: Throwable) {}
    }

    private fun onOpenFailed() {
        openFailures++
        val delay = (2000L shl (openFailures - 1).coerceAtMost(5)).coerceAtMost(60_000L)
        nextOpenAllowedAt = SystemClock.elapsedRealtime() + delay
        Log.w(TAG, "MIDI open failed ($openFailures x), next try in ${delay}ms")
    }

    /** MUST run on midiHandler thread. */
    private fun handleDevice(deviceInfo: MidiDeviceInfo) {
        if (deviceInfo.outputPortCount <= 0) return
        val mm = midiManager ?: return
        val targetId = deviceInfo.id
        isOpeningMidi = true
        try {
            mm.openDevice(deviceInfo, { device ->
                try {
                    if (device == null) {
                        Log.e(TAG, "Failed to open MIDI device id=$targetId")
                        onOpenFailed()
                        return@openDevice
                    }
                    if (destroyed) {
                        // Service udah mati duluan — jangan bocorin device.
                        try { device.close() } catch (_: Throwable) {}
                        return@openDevice
                    }
                    // Tutup port + device lama SEBELUM claim yg baru.
                    closeMidi()

                    val newPort = try {
                        device.openOutputPort(0)
                    } catch (e: Throwable) {
                        Log.e(TAG, "openOutputPort", e); null
                    }
                    if (newPort == null) {
                        Log.e(TAG, "openOutputPort returned null for id=$targetId")
                        // Device kebuka tapi port gagal -> WAJIB close device,
                        // dulu ini bocor.
                        try { device.close() } catch (_: Throwable) {}
                        onOpenFailed()
                        return@openDevice
                    }
                    newPort.connect(QwertyReceiver())
                    midiDevice = device
                    midiOutputPort = newPort
                    currentMidiDeviceId = targetId
                    openFailures = 0
                    nextOpenAllowedAt = 0L

                    val deviceName = deviceInfo.properties
                        .getString(MidiDeviceInfo.PROPERTY_NAME) ?: "Unknown"
                    broadcastMidiDevice(deviceName)
                } finally {
                    isOpeningMidi = false
                }
            }, midiHandler)
        } catch (e: Throwable) {
            isOpeningMidi = false
            onOpenFailed()
            Log.e(TAG, "midiManager.openDevice", e)
        }
    }

    private fun broadcastMidiDevice(name: String) {
        // De-dupe: kalau name-nya sama dgn yg terakhir kita kirim, skip.
        // Tanpa ini popup di FloatingPanelService bakal spam tiap rescan.
        if (name == lastBroadcastName) return
        lastBroadcastName = name
        try {
            sendBroadcast(Intent("intent.panpanpan.midi_device")
                .setPackage(packageName)
                .putExtra("name", name)
                .putExtra("connected", name.isNotEmpty()))
        } catch (e: Throwable) { Log.w(TAG, "broadcastMidiDevice", e) }
    }

    private inner class QwertyReceiver : MidiReceiver() {
        private val NOTE_ON = 0x90
        private val NOTE_OFF = 0x80

        /**
         * Latency/jitter: onSend SELALU dipanggil di thread dispatcher internal
         * MidiOutputPort yang sama. Default-nya thread itu prioritas normal,
         * jadi bisa kena preempt scheduler → not telat sesekali (jitter).
         * Sekali doang (flag di bawah) kita naikin prioritas thread itu ke
         * URGENT_AUDIO (nice -19, sama kelas dgn thread audio low-latency).
         *
         * Ini CUMA nice value, BUKAN SCHED_FIFO/real-time → gak bisa nge-starve
         * sistem, otomatis balik normal pas proses mati. Kalau OS nolak,
         * try/catch nelen errornya tanpa efek samping (zona aman).
         */
        private var priorityBoosted = false

        @Throws(IOException::class)
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            if (!priorityBoosted) {
                priorityBoosted = true
                try {
                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                } catch (_: Throwable) {}
            }
            if (count == 0) return
            val gp = iGamePad
            val end = offset + count

            var i = offset
            while (i < end) {
                val status = data[i].toInt() and 0xFF
                if (status < 0x80) { i++; continue }          // stray data byte
                if (status >= 0xF8) { i++; continue }         // realtime (clock, active sensing 0xFE, ...) = 1 byte
                if (status == 0xF0) {                         // SysEx: skip sampai 0xF7
                    i++
                    while (i < end && (data[i].toInt() and 0xFF) != 0xF7) i++
                    i++
                    continue
                }
                // Panjang pesan yg BENER per tipe. Dulu semua dianggap 3 byte,
                // jadi Program Change / Channel Pressure (2 byte) nge-geser
                // parser dan bisa "nelen" Note On/Off sesudahnya -> not hilang
                // atau not gak pernah dilepas (stuck key).
                val len = when (status and 0xF0) {
                    0xC0, 0xD0 -> 2
                    0xF0 -> when (status) { 0xF1, 0xF3 -> 2; 0xF2 -> 3; else -> 1 }
                    else -> 3
                }
                if (i + len > end) break

                val messageType = status and 0xF0
                if (messageType != NOTE_ON && messageType != NOTE_OFF) { i += len; continue }

                val noteNumber = data[i + 1].toInt() and 0x7F
                val velocity  = data[i + 2].toInt() and 0x7F
                i += len

                // Release bisa dikirim sbg NOTE_ON velocity=0 (running-status
                // optimization) — jangan dianggap press kedua.
                val isPress = (messageType == NOTE_ON) && velocity > 0
                if (noteNumber !in 21..108) continue

                if (gp == null) {
                    if (isPress && warnedNullBridge.compareAndSet(false, true)) {
                        Log.e(TAG, "qwertyKey skipped: iGamePad=null. " +
                                "Daemon belum jalan / binder gak masuk.")
                    }
                    continue
                }

                try {
                    // velocity dikirim hanya saat toggle ON & note-on (press).
                    val v = if (velocityEnabled && isPress) velocity else 0
                    gp.qwertyKey(noteNumber, isPress, v)
                } catch (e: Exception) {
                    Log.e(TAG, "qwertyKey error: $e")
                }
            }
        }
    }

    /* ---------------- NOTIFICATION ---------------- */

    private fun sendNotification() {
        val channelId = "daemon"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId,
                getString(R.string.noti_channel),
                NotificationManager.IMPORTANCE_LOW).apply {
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }

        // Pake vector kali (kecil) buat smallIcon. iconpop.png
        // 1536x1536 = ~9 MB Bitmap kalo di-decode -> rawan OOM di MIUI yg
        // memang lagi ketat memory.
        val stopIntent = PendingIntent.getBroadcast(this, 0,
            Intent("intent.panpanpan.exit").setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE)
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val customView = RemoteViews(packageName, R.layout.notification_daemon).apply {
            setOnClickPendingIntent(R.id.daemon_stop, stopIntent)
        }

        val builder = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.noti_title))
            .setContentText(getString(R.string.noti_text))
            .setSmallIcon(Icon.createWithResource(this, R.drawable.kali))
            .setColor(getColor(R.color.accent_primary))
            .setOngoing(true)
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setContentIntent(openIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        startForeground(1, builder.build())
    }

    /* ---------------- LIFECYCLE ---------------- */

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
        // Stop periodic health checks
        mainHandler.removeCallbacks(bridgeHealthRunnable)
        // Jangan tinggalin key nyangkut di virtual keyboard.
        releaseAllKeys()

        // Unlink DeathRecipient dari binder daemon
        try { currentBinder?.unlinkToDeath(binderDeathRecipient, 0) } catch (_: Throwable) {}
        currentBinder = null

        midiHandler?.removeCallbacks(rescanRunnable)
        try {
            deviceCallback?.let { midiManager?.unregisterDeviceCallback(it) }
        } catch (e: Throwable) { Log.w(TAG, "unregisterDeviceCallback", e) }
        try { midiOutputPort?.close() } catch (_: Throwable) {}
        midiOutputPort = null
        try { midiDevice?.close() } catch (_: Throwable) {}
        midiDevice = null
        if (isBroadcastRegistered) {
            try { unregisterReceiver(mBroadcastReceiver) } catch (_: Exception) {}
        }
        broadcastMidiDevice("")
        try { midiThread?.quitSafely() } catch (_: Throwable) {}
        midiThread = null
        midiHandler = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}
    override fun onInterrupt() {}

    // NOTE: onKeyEvent (Volume Up -> not 50) DIHAPUS bareng
    // flagRequestFilterKeyEvents di res/xml/a.xml. Filter key itu bikin SEMUA
    // key event di HP — termasuk tiap not dari virtual keyboard kita — mampir
    // dulu ke main thread app ini sebelum sampai ke Roblox: nambah latency
    // per not dan bisa nge-freeze input seluruh HP kalau main thread sibuk.

    companion object {
        const val TAG = "PapianoMidi"
        /** Interval periodic bridge health check (milliseconds). */
        const val BRIDGE_HEALTH_INTERVAL = 15_000L
    }
}
