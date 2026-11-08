package com.panpanpan.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Floating overlay panel — pure read-only info window:
 *   - Status indicator (service active/inactive)
 *   - MIDI input info (device name)
 *   - Opacity slider 0-100
 *
 * NO toggle service di sini. User pas iseng pencet switch malah matiin
 * accessibility, panel ke-stop, balik ke home. Daripada bikin bingung,
 * popup ini purely informational. Buat start/stop service, user balik ke
 * MainActivity (tombol Start Playing / Stop Playing).
 *
 * Tombol minimize (−) -> bubble bulet 48dp (logo dragon).
 * Tombol close (✕)    -> stopSelf() doang. TIDAK ngirim intent.panpanpan.exit
 *                        (yang itu bakal matiin AccessibilityService juga).
 *                        Set flag KEY_USER_DISMISSED biar panel gak di-auto-restart
 *                        sampe user pencet Start Playing lagi. Buat fully stop
 *                        service, user balik ke MainActivity -> Stop Playing.
 */
class FloatingPanelService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var sp: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())

    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Cached children of panel (re-bound tiap inflatePanel).
    private var tvMidi: TextView? = null
    private var tvServiceDot: TextView? = null
    private var tvServiceStatus: TextView? = null
    private var swVelocity: Switch? = null

    companion object {
        const val TAG = "PapianoFloating"
        private const val CHANNEL_ID = "floating"
        private const val NOTIF_ID = 2

        private const val PREFS = "data"

        private const val KEY_X = "floating_x"
        private const val KEY_Y = "floating_y"
        private const val KEY_BUBBLE_X = "floating_bubble_x"
        private const val KEY_BUBBLE_Y = "floating_bubble_y"

        /**
         * User explicitly closed the floating panel via ✕. While true, none
         * of the auto-launch entry points (MainActivity.onResume,
         * MidiBridgeService.onServiceConnected, MainActivity.startBtn re-press)
         * should bring the panel back. Reset only when user presses
         * Start Playing again from MainActivity.
         */
        const val KEY_USER_DISMISSED = "panel_user_dismissed"

        private const val POLL_MS = 2000L
        private const val DRAG_SLOP_DP = 5
    }

    private var lastMidiName: String = ""
    private var lastMidiConnected: Boolean = false
    private var receiverRegistered = false
    private var foregroundStarted = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    "intent.panpanpan.midi_device" -> {
                        val name = intent.getStringExtra("name") ?: ""
                        val connected = intent.getBooleanExtra(
                            "connected", name.isNotEmpty()
                        )
                        onMidiUpdate(name, connected)
                    }
                    "intent.panpanpan.exit" -> {
                        stopSelf()
                    }
                    "intent.panpanpan.force_remove_overlay" -> {
                        // Nuclear teardown: remove views IMMEDIATELY so overlay
                        // disappears even before process dies.
                        removeAllOverlayViews()
                        stopSelf()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "receiver onReceive", t)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no SYSTEM_ALERT_WINDOW permission, stopping")
            stopSelf()
            return
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sp = getSharedPreferences(PREFS, 0)

        registerReceivers()
        inflatePanel()
        startStatusPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /* ---------------- FOREGROUND NOTIFICATION ---------------- */

    private fun startForegroundCompat() {
        ensureChannel()

        // Custom neo-brutalist notification layout
        val customView = RemoteViews(packageName, R.layout.notification_floating)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(Icon.createWithResource(this, R.drawable.kali))
            .setOngoing(true)
            .setColor(getColor(R.color.accent_glow))
            .setCustomContentView(customView)
            .setCustomBigContentView(customView)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(
                Notification.FOREGROUND_SERVICE_IMMEDIATE
            )
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, builder.build())
            }
            foregroundStarted = true
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground", t)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID, "Floating Panel",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    /* ---------------- RECEIVERS ---------------- */

    private fun registerReceivers() {
        val f = IntentFilter().apply {
            addAction("intent.panpanpan.midi_device")
            addAction("intent.panpanpan.exit")
            addAction("intent.panpanpan.force_remove_overlay")
        }
        try {
            ContextCompat.registerReceiver(
                this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (t: Throwable) {
            Log.e(TAG, "registerReceiver", t)
        }
    }

    /**
     * Minta MidiBridgeService re-broadcast MIDI device state-nya yg sekarang.
     * Tanpa ini, panel yg baru di-inflate gak tau device apa yg lagi
     * connected (broadcastMidiDevice di service di-dedup pake lastBroadcastName,
     * jadi gak ada re-broadcast spontan kalau device-nya stay connected).
     */
    private fun requestMidiSnapshot() {
        try {
            sendBroadcast(
                Intent("intent.panpanpan.midi_request").setPackage(packageName)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "requestMidiSnapshot", t)
        }
    }

    /* ---------------- PANEL ---------------- */

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun inflatePanel() {
        if (panelView != null) return
        val view = try {
            LayoutInflater.from(this).inflate(R.layout.floating_panel, null)
        } catch (t: Throwable) {
            Log.e(TAG, "inflate panel", t); return
        }

        // Panel: fixed size based on shorter dimension (portrait width).
        // This keeps panel consistent across portrait & landscape.
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val shortSide = minOf(screenWidth, screenHeight)
        val panelWidth = (shortSide * 0.85).toInt()
        val panelHeight = (shortSide * 0.42).toInt()

        val params = WindowManager.LayoutParams(
            panelWidth,
            panelHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt(KEY_X, (screenWidth - panelWidth) / 2)
            y = sp.getInt(KEY_Y, dp(80))
        }
        clampParams(params, isBubble = false)

        try {
            wm.addView(view, params)
        } catch (t: Throwable) {
            Log.e(TAG, "addView panel", t); return
        }
        panelView = view
        panelParams = params

        bindPanelChildren(view)
        setupPanelHandlers(view, params)
        renderMidi(lastMidiName, lastMidiConnected)
        requestMidiSnapshot()
    }

    private fun bindPanelChildren(view: View) {
        tvMidi = view.findViewById(R.id.tv_midi)
        tvServiceDot = view.findViewById(R.id.tv_service_dot)
        tvServiceStatus = view.findViewById(R.id.tv_service_status)

        // Velocity toggle
        swVelocity = view.findViewById(R.id.sw_velocity)
        swVelocity?.setOnCheckedChangeListener(null)
        swVelocity?.isChecked = sp.getBoolean("velocity_enabled", false)
        swVelocity?.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("velocity_enabled", checked).apply()
            try {
                sendBroadcast(Intent("intent.panpanpan.set_velocity")
                    .setPackage(packageName)
                    .putExtra("enabled", checked))
            } catch (t: Throwable) {
                Log.w(TAG, "broadcast set_velocity", t)
            }
        }
    }

    private fun setupPanelHandlers(view: View, params: WindowManager.LayoutParams) {
        val header: View = view.findViewById(R.id.panel_header)
        attachDragListener(header, params, view, isBubble = false, onTap = null)

        view.findViewById<View>(R.id.btn_minimize).setOnClickListener {
            showBubble()
        }
        view.findViewById<View>(R.id.btn_close).setOnClickListener {
            // ✕ = NUCLEAR TEARDOWN (force close everything).
            // Kill daemon, disable accessibility, clear session, kill process.
            performNuclearTeardown()
        }


    }

    /* ---------------- BUBBLE ---------------- */

    private fun showBubble() {
        // remove panel
        panelView?.let { v ->
            saveCurrentParams(panelParams, isBubble = false)
            try { wm.removeView(v) } catch (_: Throwable) {}
        }
        panelView = null
        panelParams = null
        tvMidi = null; tvServiceDot = null; tvServiceStatus = null

        if (bubbleView != null) return

        val img = ImageView(this).apply {
            setImageResource(R.drawable.iconpop)
            background = null  // No background on bubble icon
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val size = dp(48)
        val params = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sp.getInt(KEY_BUBBLE_X, dp(16))
            y = sp.getInt(KEY_BUBBLE_Y, dp(120))
        }
        clampParams(params, isBubble = true)

        try {
            wm.addView(img, params)
        } catch (t: Throwable) {
            Log.e(TAG, "addView bubble", t)
            inflatePanel()
            return
        }
        bubbleView = img
        bubbleParams = params

        attachDragListener(img, params, img, isBubble = true) {
            hideBubbleShowPanel()
        }
    }

    private fun hideBubbleShowPanel() {
        bubbleView?.let { v ->
            saveCurrentParams(bubbleParams, isBubble = true)
            try { wm.removeView(v) } catch (_: Throwable) {}
        }
        bubbleView = null
        bubbleParams = null
        inflatePanel()
    }

    /* ---------------- DRAG / TOUCH ---------------- */

    private class DragState {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
    }

    private fun attachDragListener(
        target: View,
        params: WindowManager.LayoutParams,
        rootForLayout: View,
        isBubble: Boolean,
        onTap: (() -> Unit)?
    ) {
        val slop = dp(DRAG_SLOP_DP)
        val state = DragState()
        target.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    state.initX = params.x
                    state.initY = params.y
                    state.touchX = ev.rawX
                    state.touchY = ev.rawY
                    state.moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - state.touchX
                    val dy = ev.rawY - state.touchY
                    if (!state.moved && (abs(dx) > slop || abs(dy) > slop)) {
                        state.moved = true
                    }
                    if (state.moved) {
                        params.x = (state.initX + dx).toInt()
                        params.y = (state.initY + dy).toInt()
                        clampParams(params, isBubble)
                        try { wm.updateViewLayout(rootForLayout, params) }
                        catch (_: Throwable) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!state.moved && onTap != null) {
                        try { onTap.invoke() } catch (_: Throwable) {}
                    } else {
                        saveCurrentParams(params, isBubble)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun saveCurrentParams(p: WindowManager.LayoutParams?, isBubble: Boolean) {
        p ?: return
        val e = sp.edit()
        if (isBubble) e.putInt(KEY_BUBBLE_X, p.x).putInt(KEY_BUBBLE_Y, p.y)
        else          e.putInt(KEY_X, p.x).putInt(KEY_Y, p.y)
        e.apply()
    }

    private fun clampParams(p: WindowManager.LayoutParams, isBubble: Boolean) {
        val dm: DisplayMetrics = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val edgeMin = -dp(8)
        val maxX = w - dp(if (isBubble) 32 else 100)
        val maxY = h - dp(if (isBubble) 32 else 80)
        if (p.x < edgeMin) p.x = edgeMin
        if (p.y < edgeMin) p.y = edgeMin
        if (p.x > maxX) p.x = maxX
        if (p.y > maxY) p.y = maxY
    }

    /* ---------------- STATUS POLL (read-only) ---------------- */

    /** Single worker thread for status polling — no more Thread spam. */
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null

    private val statusPoll = object : Runnable {
        override fun run() {
            // Run binder call on dedicated poll thread (not main, not new Thread each time)
            pollHandler?.post {
                val running = isAccessibilityRunning()
                mainHandler.post {
                    renderStatus(running)
                }
            }
            // Adaptive interval: 5s when panel visible, pause when in bubble mode
            // (panelView != null means panel is showing)
            val interval = if (panelView != null) 5000L else 15000L
            mainHandler.postDelayed(this, interval)
        }
    }

    private fun startStatusPolling() {
        if (pollThread == null) {
            pollThread = HandlerThread("poll-thread").also { it.start() }
            pollHandler = Handler(pollThread!!.looper)
        }
        mainHandler.removeCallbacks(statusPoll)
        mainHandler.postDelayed(statusPoll, 500L)
    }

    private fun isAccessibilityRunning(): Boolean = try {
        val svc = ComponentName(packageName, MidiBridgeService::class.java.name)
            .flattenToString()
        val cur = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        cur.contains(svc)
    } catch (t: Throwable) {
        Log.w(TAG, "isAccessibilityRunning", t); false
    }

    private fun renderStatus(running: Boolean) {
        val dotColor = ContextCompat.getColor(
            this, if (running) R.color.green else R.color.red
        )
        val labelColor = ContextCompat.getColor(
            this, if (running) R.color.text_primary else R.color.text_muted
        )
        tvServiceDot?.setTextColor(dotColor)
        tvServiceStatus?.text = if (running) "Service Active" else "Service Inactive"
        tvServiceStatus?.setTextColor(labelColor)
    }

    /* ---------------- MIDI UPDATE ---------------- */

    private fun renderMidi(name: String, connected: Boolean) {
        // Cuma 2 state: Connected / Disconnected. Nama device gak ditampilin
        // (sesuai request user — info dasar aja).
        if (connected && name.isNotEmpty()) {
            tvMidi?.text = "Connected"
            tvMidi?.setTextColor(ContextCompat.getColor(this, R.color.green))
        } else {
            tvMidi?.text = "Disconnected"
            tvMidi?.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }
    }

    private fun onMidiUpdate(name: String, connected: Boolean) {
        // De-dupe: jangan toast tiap re-broadcast yg sama persis.
        if (name == lastMidiName && connected == lastMidiConnected) return
        lastMidiName = name
        lastMidiConnected = connected

        renderMidi(name, connected)
        try {
            val msg = if (connected && name.isNotEmpty())
                "MIDI Connected"
            else
                "MIDI Disconnected"
            BrutalPopup.toast(this, msg, BrutalPopup.LENGTH_SHORT)
        } catch (t: Throwable) {
            Log.w(TAG, "midi toast", t)
        }
    }

    /* ---------------- NUCLEAR TEARDOWN (Force Close) ---------------- */

    /**
     * Remove all overlay views from WindowManager IMMEDIATELY.
     * Must be called on main thread. After this, user sees no overlay.
     */
    private fun removeAllOverlayViews() {
        panelView?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        panelView = null
        panelParams = null

        bubbleView?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        bubbleView = null
        bubbleParams = null
    }

    /**
     * Kill everything: remove overlays, disable accessibility, kill daemon,
     * clear session, kill process. Called from floating panel ✕ button.
     *
     * SYNC ORDER:
     *   1. Remove overlay views FIRST (user sees immediate feedback)
     *   2. Disable accessibility
     *   3. Kill daemon
     *   4. Clear session
     *   5. Delay → kill process
     */
    private fun performNuclearTeardown() {
        // Step 1: Remove overlays IMMEDIATELY on main thread
        removeAllOverlayViews()

        Thread {
            // Step 2: Disable accessibility service
            try {
                val svcName = android.content.ComponentName(packageName,
                    MidiBridgeService::class.java.name).flattenToString()
                val cur = android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                if (cur.contains(svcName)) {
                    val list = cur.split(":").toMutableList()
                    list.remove(svcName)
                    android.provider.Settings.Secure.putString(contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        list.joinToString(":"))
                }
            } catch (_: Throwable) {}

            // Step 3: Kill daemon
            try { GamePadBridge.gamePad?.closeAndExit() } catch (_: Throwable) {}
            try { sendBroadcast(Intent("intent.panpanpan.exit")) } catch (_: Throwable) {}
            // Fallback: closeAndExit() over the binder does nothing if the
            // binder is already dead. Without this, the daemon process
            // (spawned by Shizuku/root, outside our own process) can be
            // orphaned and keep running in the background indefinitely.
            DaemonControl.kill(this)
            GamePadBridge.gamePad = null

            // Step 4: Clear session
            try {
                sp.edit()
                    .remove(KEY_USER_DISMISSED)
                    .apply()
            } catch (_: Throwable) {}

            // Step 5: Stop self + delay + kill process
            mainHandler.postDelayed({
                stopSelf()
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 500)
        }.start()
    }

    /* ---------------- CONFIGURATION CHANGE ---------------- */

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Size is fixed (based on short side) so just clamp position
        panelParams?.let {
            clampParams(it, isBubble = false)
            try { panelView?.let { v -> wm.updateViewLayout(v, it) } }
            catch (_: Throwable) {}
        }
        bubbleParams?.let {
            clampParams(it, isBubble = true)
            try { bubbleView?.let { v -> wm.updateViewLayout(v, it) } }
            catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        try { pollThread?.quitSafely() } catch (_: Throwable) {}
        pollThread = null
        pollHandler = null

        if (receiverRegistered) {
            try { unregisterReceiver(receiver) } catch (_: Throwable) {}
            receiverRegistered = false
        }
        panelView?.let {
            saveCurrentParams(panelParams, isBubble = false)
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        bubbleView?.let {
            saveCurrentParams(bubbleParams, isBubble = true)
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        panelView = null
        bubbleView = null

        if (foregroundStarted) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()
}
