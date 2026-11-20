package app.shelf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log

/** One of Meta's own screens. [intent] is null only for the library when it cannot be started directly. */
class SysLink(val id: String, val title: Int, val ic: Ic, val intent: Intent?, val fallback: Intent? = null)

/**
 * Shortcuts to the system's own screens, for the dock under the grid.
 *
 * What can be opened differs between OS versions, so nothing is assumed: every candidate is
 * looked up on the device and left out when the system would not let this app start it.
 */
object SystemLinks {
    const val LIBRARY = "library"
    private const val NAVIGATOR = "com.oculus.panelapp.library"
    private const val SHELL = "com.oculus.vrshell"
    private const val HOME_PAGE = "https://www.google.com/"

    const val PASSTHROUGH = "mr"
    private const val PT_ACTION = "com.oculus.vrshell.intent.action.UPDATE_FULL_PASSTHROUGH"
    private const val PT_RECEIVER = "com.oculus.vrshell.ShellControlBroadcastReceiver"

    private fun passthroughIntent() = Intent(PT_ACTION)
        .setClassName(SHELL, PT_RECEIVER)
        // The shell wants to know where the request comes from; these are the names it accepts
        // (found by trying: ActionButton, DoubleTap, KeyboardShortcut, QuickActionMenu, QuickControl,
        // VoiceGaze). Without one it drops the request. "DoubleTap" is what a double tap on the
        // headset sends: in the home world the shell answers it by switching between the mixed
        // reality home and the virtual environment (seen in its log: TravelController.ToggleHomeWorld),
        // inside an app by full passthrough. The other names always mean full passthrough, which hides
        // the menus.
        .putExtra("toggle_point", "DoubleTap")

    /** Whether the shell has a receiver for the passthrough request (it is not a documented interface). */
    private fun canTogglePassthrough(pm: PackageManager) =
        pm.queryBroadcastReceivers(passthroughIntent(), 0).isNotEmpty()

    /** Asks the shell to switch passthrough, the same way its own quick action does. */
    fun togglePassthrough(c: Context): Boolean = try {
        c.sendBroadcast(passthroughIntent())
        Log.i("Shelf", "passthrough toggle requested")
        true
    } catch (e: Exception) {
        Log.w("Shelf", "passthrough toggle failed", e)
        false
    }

    /**
     * A "system deep link", the way Meta documents it: the shell is started with the name of
     * one of its built-in apps (systemux://browser, systemux://store, ...) and opens that app
     * itself, exactly as its own menu does.
     */
    private fun deep(pm: PackageManager, target: String, uri: String? = null): Intent? =
        pm.getLaunchIntentForPackage(SHELL)?.apply {
            putExtra("intent_data", target)
            if (uri != null) putExtra("uri", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** An ordinary web link handed to the browser, the way any app opens a page. */
    private fun page(pm: PackageManager, pkg: String, url: String): Intent? {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (i.resolveActivity(pm) != null) i else null
    }

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
        fun add(id: String, title: Int, ic: Ic, intent: Intent?, fallback: Intent? = null) {
            val first = intent ?: fallback
            if (first != null) out.add(SysLink(id, title, ic, first, if (intent != null) fallback else null))
        }

        // always offered: when it cannot be started from here, the next Meta-button press shows it
        out.add(SysLink(LIBRARY, R.string.sys_library, Ic.GRID, screen(pm, NAVIGATOR, Store.STOCK_LIBRARY.cls)))
        // not a screen: a request to the shell to switch passthrough (mixed reality) on or off;
        // offered only where the shell has the receiver for it
        if (canTogglePassthrough(pm)) out.add(SysLink(PASSTHROUGH, R.string.sys_mr, Ic.HEADSET, null))
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
        // The shell's browser link does nothing without an address (seen on v209: it starts
        // an activity that closes at once), so it is always given a page to open.
        add(
            "browser", R.string.sys_browser, Ic.GLOBE,
            deep(pm, "systemux://browser", HOME_PAGE),
            page(pm, "com.oculus.browser", HOME_PAGE) ?: app(pm, "com.oculus.browser")
        )

        Log.i(
            "Shelf",
            "system links: " + out.joinToString { it.id + if (it.intent == null) "(via Meta button)" else "" }
        )
        return out
    }
}
