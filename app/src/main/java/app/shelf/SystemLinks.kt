package app.shelf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/** One of Meta's own screens. [intent] is null only for the library when it cannot be started directly. */
class SysLink(val id: String, val title: Int, val ic: Ic, val intent: Intent?)

/**
 * Shortcuts to the system's own screens, for the dock under the grid.
 *
 * What can be opened differs between OS versions, so nothing is assumed: every candidate is
 * looked up on the device and left out when the system would not let this app start it.
 */
object SystemLinks {
    const val LIBRARY = "library"
    private const val NAVIGATOR = "com.oculus.panelapp.library"

    /** A specific screen of a system app; only when other apps are allowed to start it. */
    private fun screen(pm: PackageManager, pkg: String, cls: String): Intent? = try {
        val info = pm.getActivityInfo(ComponentName(pkg, cls), 0)
        if (info.exported && info.permission == null && info.isEnabled) {
            Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /** The first of these apps that is installed and can be launched. */
    private fun app(pm: PackageManager, vararg pkgs: String): Intent? {
        for (p in pkgs) {
            pm.getLaunchIntentForPackage(p)?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        return null
    }

    private fun action(pm: PackageManager, action: String): Intent? {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (i.resolveActivity(pm) != null) i else null
    }

    fun build(c: Context): List<SysLink> {
        val pm = c.packageManager
        val out = ArrayList<SysLink>()
        fun add(id: String, title: Int, ic: Ic, intent: Intent?) {
            if (intent != null) out.add(SysLink(id, title, ic, intent))
        }

        // always offered: when it cannot be started from here, the next Meta-button press shows it
        out.add(SysLink(LIBRARY, R.string.sys_library, Ic.GRID, screen(pm, NAVIGATOR, Store.STOCK_LIBRARY.cls)))
        add(
            "settings", R.string.sys_settings, Ic.GEAR,
            app(pm, "com.oculus.panelapp.settings") ?: action(pm, Settings.ACTION_SETTINGS)
        )
        add("store", R.string.sys_store, Ic.BAG, app(pm, "com.oculus.store"))
        add(
            "quick", R.string.sys_quick, Ic.SWITCH,
            screen(pm, NAVIGATOR, "com.oculus.navigator.quickcontrols.app.NavigatorQuickControlsActivity")
        )
        add(
            "notifications", R.string.sys_notifications, Ic.BELL,
            screen(pm, NAVIGATOR, "com.oculus.navigator.notifications.app.NotificationsActivity")
        )
        add(
            "profile", R.string.sys_profile, Ic.USER,
            screen(pm, NAVIGATOR, "com.oculus.navigator.me.app.NavigatorMeTabActivity")
        )
        add("people", R.string.sys_people, Ic.PEOPLE, app(pm, "com.oculus.socialplatform"))
        add("camera", R.string.sys_camera, Ic.CAMERA, app(pm, "com.oculus.metacam"))
        add("browser", R.string.sys_browser, Ic.GLOBE, app(pm, "com.oculus.browser"))

        Log.i(
            "Shelf",
            "system links: " + out.joinToString { it.id + if (it.intent == null) "(via Meta button)" else "" }
        )
        return out
    }
}
