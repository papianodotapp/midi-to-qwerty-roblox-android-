package com.panpanpan.app

/**
 * In-process bridge holder for the daemon's IGamePad binder.
 *
 * WHY: The MIDI file player lives in FloatingPanelService and used to deliver
 * every note via sendBroadcast() -> BroadcastReceiver in MidiBridgeService, which
 * then called the binder. That broadcast is dispatched by ActivityManager and
 * is NOT real-time — it adds tens of ms of latency + jitter per note even
 * though both components run in the SAME process.
 *
 * By publishing the live binder proxy here, the file player can call
 * iGamePad.qwertyKey() DIRECTLY (no broadcast hop), cutting the felt delay to
 * near the binder transaction cost only.
 *
 * MidiBridgeService OWNS the binder lifecycle: it sets [gamePad] when the daemon
 * connects and nulls it on binder death. Readers MUST null-check and fall back
 * to the broadcast path if the bridge isn't ready (e.g. during daemon respawn).
 *
 * Fields are @Volatile: written by MidiBridgeService (main thread), read by the
 * midi-player HandlerThread.
 */
object GamePadBridge {
    @JvmField @Volatile
    var gamePad: IGamePad? = null

    /** Mirror of the velocity toggle so direct callers can gate without prefs. */
    @JvmField @Volatile
    var velocityEnabled: Boolean = false
}
