package app.shelf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Fallback autostart for when the accessibility service is not enabled.
 * Works only if the app may start activities from the background
 * (install.ps1 grants SYSTEM_ALERT_WINDOW for that).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.init(c)
        if (!Store.autostartBoot) return
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            WatchService.openLauncher(c.applicationContext, "boot receiver")
            pending.finish()
        }, 6000)
    }
}
