package app.shelf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Fallback autostart for when the accessibility service is not enabled. Works only where the
 * system lets the app start an activity from the background; normally the service does this.
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
