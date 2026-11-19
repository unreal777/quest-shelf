package app.shelf

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Background piece of the launcher. As an accessibility service it is started by the
 * system at boot, is allowed to open activities from the background, and sees which
 * window / hovered element is in front. That gives three hooks:
 *  - open the launcher after boot,
 *  - open it when the headset wakes up,
 *  - open it instead of the stock library (when an event matches a saved trigger).
 */
class WatchService : AccessibilityService() {

    companion object {
        private const val TAG = "Shelf"
        private const val LOG_SIZE = 120
        // Not panels of the desktop: the shell's bars and dialogs, and Meta's menu with its library,
        // which shows up for a moment whenever the desktop is left without a single panel.
        private val SHELL_CHROME = setOf(
            "com.oculus.vrshell", "com.oculus.systemux", "com.oculus.guardian",
            "com.oculus.panelapp.library", "com.meta.systemui",
        )

        /** When the launcher was last moved to the middle: never twice in a row. */
        @Volatile
        private var lastMove = 0L

        @Volatile
        var running = false
            private set

        @Volatile
        private var learnUntil = 0L

        private val log = ArrayDeque<Trigger>()
        private val captured = LinkedHashMap<String, Trigger>()

        fun beginLearning(ms: Long) {
            synchronized(captured) { captured.clear() }
            learnUntil = System.currentTimeMillis() + ms
        }

        /** Distinct events seen during the learning window, oldest first. */
        fun takeCaptured(): List<Trigger> = synchronized(captured) {
            val l = captured.values.toList()
            captured.clear()
            l
        }

        /** Recent distinct events, newest first. */
        fun recent(): List<Trigger> = synchronized(log) {
            val seen = HashSet<String>()
            // asReversed(): List.reversed() would bind to the Java 21 member missing on-device
            log.toList().asReversed().filter { seen.add(it.key()) }
        }

        // The user asked for Meta's own library: leave it alone until it is closed again.
        @Volatile
        private var bypass = false

        @Volatile
        private var bypassSeen = false

        @Volatile
        private var bypassUntil = 0L

        @Volatile
        private var bypassSince = 0L

        /**
         * Lets the stock library open once without being replaced. The pass ends when its
         * window goes away; the time limits keep a missed event from switching the
         * replacement off for good.
         */
        fun allowStockLibrary() {
            bypassSeen = false
            bypassSince = System.currentTimeMillis()
            bypassUntil = bypassSince + 60_000
            bypass = true
            Log.i(TAG, "stock library allowed once")
        }

        /**
         * The user is back in the launcher, so the pass is over even if the library's window
         * was never seen closing. Ignored right after the pass was given: opening the
         * library can bounce the focus through the launcher for a moment.
         */
        fun endStockLibraryPass() {
            if (bypass && System.currentTimeMillis() - bypassSince > 3000) {
                bypass = false
                Log.i(TAG, "stock library pass ended: back in the launcher")
            }
        }

        fun openLauncher(c: Context, why: String) {
            Log.i(TAG, "open launcher: $why")
            try {
                c.startActivity(
                    Intent(c, Panel.target()).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "could not open launcher ($why)", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastFire = 0L
    private var receiverRegistered = false
    private var libraryShown = false
    private val looker = Executors.newSingleThreadScheduledExecutor()
    private var pendingLook: ScheduledFuture<*>? = null

    /** When another panel was first seen open, 0 = none is (used on [looker] only). */
    private var othersSince = 0L

    // all three belong to [looker]
    private val windowPkg = HashMap<Int, String>()
    private val silentSince = HashMap<Int, Long>()
    private var lastSeen = ""

    /** Label of the stock library activity in the current language; a starting guess for its window title. */
    private val stockLibraryLabel: String by lazy {
        try {
            val cn = ComponentName(Store.STOCK_LIBRARY.pkg, Store.STOCK_LIBRARY.cls)
            packageManager.getActivityInfo(cn, 0).loadLabel(packageManager).toString()
        } catch (e: Exception) {
            ""
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (Store.autostartWake) {
                handler.postDelayed({ openLauncher(this@WatchService, "wake") }, 2500)
            }
        }
    }

    override fun onServiceConnected() {
        Store.init(this)
        running = true
        Log.i(TAG, "watch service connected")

        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true

        // keep the list of owned games fresh even if the launcher has not been opened yet
        handler.postDelayed({
            Meta.init(this)
            Owned.init(this)
            Owned.scanIfStale()
            Owned.requestCovers()
        }, 15000)

        // The system binds accessibility services right after boot.
        if (Store.autostartBoot && SystemClock.elapsedRealtime() < 180_000) {
            handler.postDelayed({ openLauncher(this, "boot") }, 8000)
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val type = e.eventType
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            checkLibraryWindow()
            checkOtherWindows()
            return
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_HOVER_ENTER &&
            type != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) return

        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        val cls = e.className?.toString() ?: ""
        val text = e.text.joinToString(" ") { it.toString() }
            .ifBlank { e.contentDescription?.toString() ?: "" }
            .trim().take(80)
        val ev = Trigger(type, pkg, cls, text)
        Log.d(TAG, "event type=$type pkg=$pkg cls=$cls text=\"$text\"")

        synchronized(log) {
            log.addLast(ev)
            while (log.size > LOG_SIZE) log.removeFirst()
        }

        // The stock library reports its window title here; remember it (it is localized)
        // so checkLibraryWindow() can recognise the window before it gets focus.
        if (Store.STOCK_LIBRARY.matches(ev) && text.isNotEmpty() && text != Store.libraryTitle) {
            Store.libraryTitle = text
        }

        val now = System.currentTimeMillis()
        if (now < learnUntil) {
            synchronized(captured) {
                captured.remove(ev.key())
                captured[ev.key()] = ev
            }
            return
        }

        if (Store.intercept && Store.triggers().any { it.matches(ev) }) {
            fire("trigger ${ev.key()}")
        }
    }

    /**
     * Early hook. In the Navigator UI the library window shows up in the window list as soon
     * as the Meta button is pressed, but its "window state changed" event arrives only when
     * the user points at it — seconds later. So watch the window list and react on the
     * moment the library window appears.
     */
    private fun checkLibraryWindow() {
        if (System.currentTimeMillis() < learnUntil) return
        val title = Store.libraryTitle.ifEmpty { stockLibraryLabel }
        if (title.isEmpty()) return
        val shown = try {
            windows.any {
                it.title?.toString() == title &&
                    it.root?.packageName?.toString() == Store.STOCK_LIBRARY.pkg
            }
        } catch (e: Exception) {
            false
        }
        val appeared = shown && !libraryShown
        libraryShown = shown
        if (bypass) {
            val now = System.currentTimeMillis()
            if (shown && !bypassSeen) {
                bypassSeen = true
                bypassUntil = now + 30 * 60_000
            } else if ((!shown && bypassSeen) || now > bypassUntil) {
                bypass = false
                Log.i(TAG, "stock library pass ended")
            }
        }
        // active only while the stock-library trigger is still in the user's list
        if (appeared && Store.intercept && Store.triggers().any { it.key() == Store.STOCK_LIBRARY.key() }) {
            fire("library window appeared")
        }
    }

    /**
     * A second panel takes the middle and pushes the launcher to the side, and the system leaves
     * it there after that panel is closed. Starting the launcher again changes nothing (the shell
     * ignores a relaunch of a panel that is already shown); only a window that is opened anew is
     * put in the middle. So once the last other panel is gone, the launcher opens its second
     * window, which lands in the middle, and that one closes the first (see Panel.moveToMiddle).
     */
    private fun checkOtherWindows() {
        if (!Store.recenter) return
        // many events come in a burst: look once, a moment after the last of them
        pendingLook?.cancel(false)
        pendingLook = looker.schedule({ look(0) }, 200, TimeUnit.MILLISECONDS)
    }

    /**
     * Which desktop panels are open besides the launcher: 1 = at least one, 0 = none,
     * -1 = cannot tell yet (a window that does not answer, as an app does while it starts).
     *
     * Runs on [looker]: asking a window for its content can take seconds, and on the main
     * thread that froze the launcher while its own window was going away.
     */
    private fun otherPanels(): Pair<Boolean, Int> {
        var mine = false
        var others = false
        var unknown = false
        val list = try {
            windows
        } catch (e: Exception) {
            return Pair(false, -1)
        }
        val now = System.currentTimeMillis()
        val seen = HashSet<Int>()
        val what = StringBuilder()
        for (w in list) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val id = w.id
            seen.add(id)
            // a window keeps its package, so each one is asked only once
            val pkg = windowPkg[id] ?: try {
                w.root?.packageName?.toString()
            } catch (e: Exception) {
                null
            }?.also { windowPkg[id] = it }
            when {
                pkg == null -> {
                    // silent for long: not an app that is still starting, leave it out
                    val since = silentSince.getOrPut(id) { now }
                    if (now - since < 4_000) {
                        unknown = true
                        what.append(" ?").append(id)
                    }
                }
                pkg == packageName -> mine = true
                pkg in SHELL_CHROME -> {}
                else -> {
                    others = true
                    what.append(' ').append(pkg)
                }
            }
        }
        windowPkg.keys.retainAll(seen)
        silentSince.keys.retainAll(seen)
        lastSeen = what.toString().trim()
        return Pair(mine, if (others) 1 else if (unknown) -1 else 0)
    }

    private fun lookAgain(attempt: Int, ms: Long) {
        pendingLook?.cancel(false)
        pendingLook = looker.schedule({ look(attempt) }, ms, TimeUnit.MILLISECONDS)
    }

    /** [attempt] 0 is the first look after an event; later ones repeat it until the picture is clear. */
    private fun look(attempt: Int) {
        val (mine, state) = otherPanels()
        val now = System.currentTimeMillis()
        if (state == 1) {
            if (othersSince == 0L) {
                othersSince = now
                Log.i(TAG, "another panel is open: $lastSeen")
            }
            return
        }
        if (othersSince == 0L) {
            // a window that does not answer yet may be a panel that is just opening: ask again,
            // or its closing would go unnoticed later
            if (state == -1 && attempt < 8) lookAgain(attempt + 1, 600)
            return
        }
        // A starting app drops out of the list for a moment, so "gone" has to hold for a while.
        if (attempt == 0 || (state == -1 && attempt < 8)) {
            lookAgain(attempt + 1, 500)
            return
        }
        if (state == -1) {
            Log.i(TAG, "cannot tell whether the other panel is gone: $lastSeen")
            return
        }
        othersSince = 0L
        if (mine && Store.recenter && !bypass && now - lastMove > 2_500) {
            lastMove = now
            handler.post { Panel.moveToMiddle(this, "other window closed") }
        } else {
            Log.i(TAG, "other panel gone, staying: launcher shown=$mine pass=$bypass")
        }
    }

    private fun fire(why: String) {
        val now = System.currentTimeMillis()
        if (bypass) {
            if (now <= bypassUntil) return
            bypass = false
        }
        if (now - lastFire < 1500) return
        lastFire = now
        openLauncher(this, why)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
        }
        handler.removeCallbacksAndMessages(null)
        looker.shutdownNow()
        super.onDestroy()
    }
}
