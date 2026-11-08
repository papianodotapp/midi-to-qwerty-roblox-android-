package com.panpanpan.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager

/**
 * Every accessibility-enable call site in this app used to just check
 * "does the Settings.Secure string already contain our service name" before
 * deciding whether to write it — MainActivity, PermissionGateActivity, and
 * MidiBridgeService's auto-grant all did this same check independently.
 *
 * That string reflects what Android was TOLD, not what it actually bound.
 * On Android 13+, a sideloaded app's Accessibility Service can be blocked by
 * "Restricted settings" — the write to Settings.Secure still succeeds (it's
 * a raw content-provider write, not gated), but system_server's
 * AccessibilityManagerService silently refuses to actually bind the service.
 * Android only re-evaluates binding on a fresh OFF->ON transition of that
 * string; if our service is already listed (even from an earlier blocked
 * attempt, before the user allowed restricted settings), every one of those
 * call sites sees "already contains it" and never writes again — so a
 * service that's listed-but-never-bound stays that way forever, with no
 * visible error anywhere, which matches a device sitting in "Recovering..."
 * indefinitely despite Shizuku being alive and the daemon files being fine.
 *
 * This checks the REAL bound state via AccessibilityManager and forces a
 * remove-then-add cycle whenever the string and reality disagree.
 */
object AccessibilityGate {
    private const val TAG = "PapianoMidi"

    private fun serviceComponent(context: Context): ComponentName =
        ComponentName(context.packageName, MidiBridgeService::class.java.name)

    /** True only if Android actually bound the service, not just "listed in settings". */
    @JvmStatic
    fun isActuallyBound(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return false
            val svc = serviceComponent(context)
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val si = info.resolveInfo?.serviceInfo
                    si != null && si.packageName == svc.packageName && si.name == svc.className
                }
        } catch (t: Throwable) {
            Log.w(TAG, "isActuallyBound", t)
            false
        }
    }

    /**
     * Ensure the service is both listed AND actually bound. Confirmed on a
     * real device: a plain "add to the list" write is NOT enough to make
     * AccessibilityManagerService actually bind, even on a fresh install —
     * only manually toggling the service OFF then back ON in system
     * Settings made it connect. A straight single write from empty to
     * listed apparently doesn't reliably trigger a (re-)bind on some
     * devices/firmwares, but an explicit OFF->ON transition does.
     *
     * So this always forces that OFF->ON cycle whenever the service isn't
     * both listed and bound already — not only in the "listed but stuck
     * unbound" case. The two writes need a real gap between them: writing
     * OFF then immediately ON back-to-back risks the OFF change notification
     * being superseded before AccessibilityManagerService's observer reacts
     * to it, since both writes land in the same instant. This 300ms is not
     * a "wait then hope it worked" verification guess (the anti-pattern
     * removed elsewhere in this app) — it's a deliberate gap between two
     * writes we are making ourselves, needed for the system to observe the
     * first one before we make the second.
     */
    @JvmStatic
    fun ensureEnabled(context: Context) {
        try {
            val svcName = serviceComponent(context).flattenToString()
            val cr = context.contentResolver
            val current = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val listed = current.contains(svcName)
            val bound = isActuallyBound(context)

            if (listed && bound) return // genuinely fine, nothing to do

            Log.w(TAG, "Accessibility service not actively bound — forcing OFF->ON cycle")

            if (listed) {
                val without = stripService(current, svcName)
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)
                Thread.sleep(300)
            }

            val base = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val next = if (base.isEmpty()) svcName else "$svcName:$base"
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        } catch (t: Throwable) {
            Log.e(TAG, "ensureEnabled", t)
        }
    }

    private fun stripService(current: String, svcName: String): String =
        current.split(":").filter { it.isNotEmpty() && it != svcName }.joinToString(":")
}
