package app.shelf

import android.app.Dialog
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * The launcher has two identical windows, [MainActivity] and [MainActivity2], and shows one
 * of them. The shell puts a newly opened window in the middle of the desktop and offers no
 * way to move one that is already open, so "move to the middle" is done by opening the other
 * window, which then closes the one left at the side.
 */
object Panel {
    /** What the user was looking at, to come back to the same place. */
    class Carry(val tab: String, val query: String, val genre: String, val mark: String, val first: Int, val at: Long)

    @Volatile
    var current: MainActivity? = null

    @Volatile
    var carry: Carry? = null

    private fun alive(a: MainActivity?) = a != null && !a.isFinishing && !a.isDestroyed

    /** The window to start so that the launcher which is already open comes forward. */
    fun target(): Class<out MainActivity> = current?.takeIf { alive(it) }?.javaClass ?: MainActivity::class.java

    fun moveToMiddle(c: Context, why: String): Boolean {
        val a = current
        if (a == null || !alive(a) || !a.mayMove()) return false
        android.util.Log.i("Shelf", "moving to the middle: $why")
        carry = a.snapshot()
        val other = if (a is MainActivity2) MainActivity::class.java else MainActivity2::class.java
        return try {
            c.applicationContext.startActivity(Intent(c, other).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            android.util.Log.w("Shelf", "could not open the second window", e)
            false
        }
    }
}

/** The launcher's second window: the same screen under another name (see [Panel]). */
class MainActivity2 : MainActivity()

open class MainActivity : Activity() {

    /** A label an app can carry. */
    private class Mark(val id: String, val title: Int, val ic: Ic, val color: Int)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val DAY_MS = 24L * 3600 * 1000

        /** Free strip left and right of the content, where the background picture shows as it is. */
        const val SIDE = 48

        /** Less play time than this (and not touched since) reads as "tried it and dropped it". */
        const val SHORT_PLAY_MS = 15L * 60 * 1000

        /** The first four are worked out automatically, the other two are the user's own. */
        val MARKS = listOf(
            Mark("new", R.string.mark_new, Ic.SPARK, Pal.GREEN),
            Mark("never", R.string.mark_never, Ic.PLAY_LINE, Pal.SKY),
            Mark("demo", R.string.mark_demo, Ic.FLASK, Pal.PURPLE),
            Mark("short", R.string.mark_short, Ic.CLOCK, Pal.SLATE),
            Mark("bad", R.string.mark_bad, Ic.SAD, Pal.RED),
            Mark("done", R.string.mark_done, Ic.TROPHY, Pal.GOLD),
        )

        /** id -> label; "recent" is the default, "time" is offered only with usage access. */
        val SORTS = listOf(
            "recent" to R.string.sort_recent,
            "name" to R.string.sort_name,
            "name_desc" to R.string.sort_name_desc,
            "installed" to R.string.sort_installed,
            "updated" to R.string.sort_updated,
            "time" to R.string.sort_time,
        )

        /** Catalog spellings that mean the same genre (keys are lower case with "_" for the rest). */
        val GENRE_ALIAS = mapOf(
            "rpg" to "role_playing",
            "fitness_wellness" to "fitness",
            "sports_training" to "training",
            "utilities" to "utility",
            "learning" to "educational",
            "community" to "social",
            "hangout" to "social",
            "party_game" to "party",
            "media_player" to "media",
            "media_streaming" to "media",
            "travel_exploration" to "travel",
            "kids_family" to "family",
            "art_creativity" to "creativity",
        )
        val NOT_KEY = Regex("[^a-z0-9]+")
    }

    private class Holder(
        val box: View,
        val icon: ImageView,
        val star: ImageView,
        val more: ImageView,
        val badges: List<ImageView>,
        val absent: ImageView,
        val label: TextView,
    ) {
        var pkg = ""
        var fav = false
        var hot = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: Kit
    private var links: List<SysLink> = emptyList()

    private lateinit var search: EditText
    private lateinit var filterSlot: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var filterScroll: HorizontalScrollView
    private lateinit var filterRow: LinearLayout
    private lateinit var heroBox: FrameLayout
    private lateinit var runBox: FrameLayout
    private lateinit var setupBox: FrameLayout
    private var setupKey = "-"
    private var setupDialog: Dialog? = null
    private var runKey = "-"

    /** The game the user has just told to quit: its bar is not shown while that is being checked. */
    private var quitting: String? = null
    private lateinit var backdrop: Backdrop

    /** Grid position to come back to after the window was made anew, -1 = none. */
    private var restoreFirst = -1
    private lateinit var genreBox: TextView
    private var wallKey = "-"
    private lateinit var grid: DragGrid
    private lateinit var empty: TextView

    private var all: List<AppEntry> = emptyList()
    private var shown: List<AppEntry> = emptyList()

    /** Everything that takes part in the user's order, in that order. */
    private var arranged: List<AppEntry> = emptyList()
    private var rank: Map<String, Int> = emptyMap()
    private var loaded = false
    private var reloadQueued = false
    private var migrated = false

    /** all | fav | side | sys | hidden | f:<folder name> */
    private var tab = "all"
    private var query = ""

    /** Selected genre label or "" for all. */
    private var genre = ""

    /** all | yes (installed) | no (owned, not installed) */
    private var inst = "all"

    /** Selected mark id from [MARKS] or "". */
    private var mark = ""

    /** Package being dragged, or null. */
    private var moving: String? = null

    private var favs: Set<String> = emptySet()
    private var bad: Set<String> = emptySet()
    private var done: Set<String> = emptySet()
    private var notShort: Set<String> = emptySet()
    private var notNew: Set<String> = emptySet()
    private var notNever: Set<String> = emptySet()
    private var hidden: Set<String> = emptySet()
    private var names: Map<String, String> = emptyMap()
    private var usageAccess = false

    // what the filter sheet offers, as counted by the last refresh
    private var countAll = 0
    private var countYes = 0
    private var countNo = 0
    private var markCounts: Map<Mark, Int> = emptyMap()
    private var genreCounts: Map<String, Int> = emptyMap()
    private var funnelState = -1
    private var genreShownOn: Boolean? = null

    private var heroWanted = false
    private var heroKey = ""
    private var heroCheckQueued = false

    /** catalog genre -> label in the current language */
    private val genreNames = HashMap<String, String>()

    private fun s(id: Int, vararg args: Any): String = getString(id, *args)
    private fun dp(v: Int) = ui.dp(v)

    // ---------------------------------------------------------------- setup

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Lang.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Meta.init(this)
        Owned.init(this)
        ui = Kit(this)
        inst = Store.installFilter

        // the newest window is the launcher; the one it replaces goes once this one is on screen
        val older = Panel.current
        Panel.current = this
        if (older != null && older !== this) {
            handler.postDelayed({
                if (!older.isFinishing && !older.isDestroyed) older.finishAndRemoveTask()
            }, 450)
        }
        Panel.carry?.let { c ->
            Panel.carry = null
            if (System.currentTimeMillis() - c.at < 15_000) {
                tab = c.tab
                query = c.query
                genre = c.genre
                mark = c.mark
                restoreFirst = c.first
            }
        }

        val frame = FrameLayout(this).apply { background = ui.backdrop() }
        // a game cover behind everything: clear along the edges, blurred and veiled towards the middle
        backdrop = Backdrop(this).apply { visibility = View.GONE }
        frame.addView(backdrop, FrameLayout.LayoutParams(MATCH, MATCH))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(SIDE), dp(22), dp(SIDE), dp(10))
            // takes initial focus so the keyboard does not pop up over the grid
            isFocusableInTouchMode = true
        }

        // ---- top bar: search on the left, round icon buttons on the right
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        search = EditText(this).apply {
            hint = s(R.string.search_hint)
            setHintTextColor(Pal.DIM)
            setTextColor(Pal.TEXT)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = ui.round(Pal.GLASS, 24)
            setPadding(dp(18), 0, dp(18), 0)
            setCompoundDrawablesRelative(ui.icon(Ic.SEARCH, Pal.DIM, 20), null, null, null)
            compoundDrawablePadding = dp(12)
            if (query.isNotEmpty()) setText(query)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                refresh()
            }
        })
        top.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
        filterSlot = FrameLayout(this)
        top.addView(filterSlot, LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(ui.iconButton(Ic.SORT, s(R.string.sort_title)) { showSortMenu() })
        top.addView(ui.iconButton(Ic.SLIDERS, s(R.string.settings)) { showSettings() })
        root.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // ---- tabs
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabBar.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        genreBox = ui.text("", 14f, Pal.TEXT).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(40)
            maxWidth = dp(240)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), 0, dp(10), 0)
            compoundDrawablePadding = dp(8)
            isClickable = true
            isFocusable = true
            setOnClickListener { showGenres() }
            visibility = View.GONE
        }
        tabBar.addView(genreBox, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        tabBar.addView(ui.iconButton(Ic.PLUS, s(R.string.new_folder), size = 40, plain = true) {
            prompt(s(R.string.new_folder), "") { name ->
                Store.addFolder(name)
                tab = "f:$name"
                refresh()
            }
        })
        root.addView(tabBar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // ---- installed / not installed, one click away; then the mark filter, removable
        filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filterRow)
            visibility = View.GONE
        }
        root.addView(filterScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // ---- a reminder for as long as the launcher has not been switched on in the system
        setupBox = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(setupBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // ---- the game that is running under the launcher: back to it, or quit it
        runBox = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(runBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // ---- "continue" and most played; gives its room to the grid once the grid is scrolled
        heroBox = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(heroBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        // ---- the grid
        val body = FrameLayout(this)
        grid = DragGrid(this).apply {
            numColumns = GridView.AUTO_FIT
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(8)
            horizontalSpacing = dp(8)
            selector = ColorDrawable(0)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setPadding(0, dp(8), 0, dp(16))
        }
        grid.setOnItemClickListener { _, _, pos, _ -> launch(shown[pos]) }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            val a = shown[pos]
            // tiles outside the user's order (system, hidden) are not carried around
            if (a.pkg in rank) {
                ui.hideTip()
                moving = a.pkg
                grid.startDrag(pos)
            } else {
                showDetails(a)
            }
            true
        }
        grid.onDrop = { from, at -> shown.getOrNull(from)?.let { dropAt(it, at) } }
        grid.onDragEnd = {
            moving = null
            refresh()
        }
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                // this can arrive in the middle of a layout pass: decide about the hero right after it
                if (!heroCheckQueued) {
                    heroCheckQueued = true
                    grid.post {
                        heroCheckQueued = false
                        updateHeroVisibility()
                    }
                }
            }
        })
        applyTileSize()
        empty = ui.text(s(R.string.loading), 17f, Pal.DIM).apply { gravity = Gravity.CENTER }
        body.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        body.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(6) })

        // ---- the way back to Meta's own screens: its library, settings, store and the rest
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.round(Pal.GLASS, 32)
            setPadding(dp(0), dp(6), dp(8), dp(6))
        }
        links = SystemLinks.build(this)
        for (link in links) {
            // Meta's library is what this launcher stands in for: the way to it is in the settings
            if (link.id == SystemLinks.LIBRARY) continue
            dock.addView(ui.iconButton(link.ic, s(link.title), size = 46, plain = true) { openLink(link) })
        }
        root.addView(
            dock,
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
        )

        frame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        ui.tipHost = frame
        setContentView(frame)

        // covers, names and the owned list arrive in the background; redraw at most every 2 s
        val later = {
            handler.post {
                if (!reloadQueued) {
                    reloadQueued = true
                    handler.postDelayed({
                        reloadQueued = false
                        reload()
                    }, 2000)
                }
            }
            Unit
        }
        Meta.onUpdate = later
        Owned.onUpdate = later
    }

    override fun onResume() {
        super.onResume()
        reload()
        WatchService.endStockLibraryPass()
        openRequested(intent)
        // back from a system screen: show what has changed there
        if (setupDialog?.isShowing == true) {
            showSetup()
        } else if (!Store.setupSeen) {
            Store.setupSeen = true
            if (!serviceEnabled() || !Apps.hasUsageAccess(this)) showSetup()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /** `--es open <id>` on the launch intent opens that system shortcut, e.g. from a script. */
    private fun openRequested(i: Intent?) {
        val id = i?.getStringExtra("open") ?: return
        i.removeExtra("open")
        links.firstOrNull { it.id == id }?.let { openLink(it) }
    }

    /** Not while a tile is being carried: the window going away would drop it. */
    internal fun mayMove() = !grid.isDragging

    internal fun snapshot() =
        Panel.Carry(tab, query, genre, mark, grid.firstVisiblePosition, System.currentTimeMillis())

    override fun onDestroy() {
        if (Panel.current === this) Panel.current = null
        Meta.onUpdate = null
        Owned.onUpdate = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun reload() {
        thread {
            val list = try {
                Apps.load(this)
            } catch (e: Exception) {
                android.util.Log.e("Shelf", "load failed", e)
                emptyList()
            }
            runOnUiThread {
                if (!isDestroyed) {
                    all = list
                    loaded = true
                    // a redraw in the middle of carrying a tile would pull it out of the hand
                    if (!grid.isDragging) refresh()
                    if (restoreFirst >= 0) {
                        grid.setSelection(restoreFirst)
                        restoreFirst = -1
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- content

    /** Name shown on the tile: the user's own, else the store's, else what the APK says. */
    private fun label(a: AppEntry) = names[a.pkg] ?: a.storeName.ifEmpty { a.sysLabel }

    /** Genre in the current language; "" means "not a real genre" (the catalog's placeholder). */
    private fun genreLabel(g: String): String = genreNames.getOrPut(g) {
        var key = g.trim().lowercase().replace(NOT_KEY, "_").trim('_')
        if (key.isEmpty() || key == "unknown") return@getOrPut ""
        key = GENRE_ALIAS[key] ?: key
        val id = resources.getIdentifier("g_$key", "string", packageName)
        if (id != 0) getString(id) else g.trim()
    }

    /** Genre labels of an app, de-duplicated (two catalog spellings can map to one label). */
    private fun genresOf(a: AppEntry): Set<String> =
        a.genres.mapNotNullTo(LinkedHashSet()) { genreLabel(it).ifEmpty { null } }

    /** Favorites, then what can be started right now, then the chosen sort. */
    private fun comparator(sort: String): Comparator<AppEntry> {
        val byName = compareBy<AppEntry> { label(it).lowercase() }
        val base: Comparator<AppEntry> = when (sort) {
            "name" -> byName
            "name_desc" -> byName.reversed()
            "installed" -> compareByDescending<AppEntry> { it.installed }.then(byName)
            "updated" -> compareByDescending<AppEntry> { it.updated }.then(byName)
            "time" -> compareByDescending<AppEntry> { it.playTime }.then(byName)
            // last played first; a game that was just installed counts as fresh too
            else -> compareByDescending<AppEntry> { maxOf(it.lastUsed, it.installed) }.then(byName)
        }
        return compareByDescending<AppEntry> { it.pkg in favs }
            .thenBy { it.notInstalled }
            .then(base)
    }

    /** The chosen sort, with the tiles the user placed by hand put back in their places. */
    private fun arrange(universe: List<AppEntry>, sort: String): List<AppEntry> {
        val pins = Store.pins()
        val out = ArrayList(universe.filter { it.pkg !in pins }.sortedWith(comparator(sort)))
        for (a in universe.filter { it.pkg in pins }.sortedBy { pins.getValue(it.pkg) }) {
            out.add(minOf(pins.getValue(a.pkg), out.size), a)
        }
        return out
    }

    /**
     * The old "custom order" froze the whole list. Carry over only the tiles that were
     * really moved: the ones standing out of the alphabetical run the rest was frozen in.
     */
    private fun migrateOldOrder(universe: List<AppEntry>) {
        val old = Store.oldOrder()
        if (Store.sort == "manual") Store.sort = "recent"
        if (old.isEmpty()) return
        Store.dropOldOrder()
        if (Store.pins().isNotEmpty()) return
        val expected = HashMap<String, Int>()
        universe.sortedWith(comparator("name")).forEachIndexed { i, a -> expected[a.pkg] = i }
        val seq = old.filter { it in expected }
        val n = seq.size
        val len = IntArray(n) { 1 }
        val prev = IntArray(n) { -1 }
        var best = -1
        for (i in 0 until n) {
            for (j in 0 until i) {
                if (expected.getValue(seq[j]) < expected.getValue(seq[i]) && len[j] + 1 > len[i]) {
                    len[i] = len[j] + 1
                    prev[i] = j
                }
            }
            if (best < 0 || len[i] > len[best]) best = i
        }
        val unmoved = HashSet<String>()
        var k = best
        while (k >= 0) {
            unmoved.add(seq[k])
            k = prev[k]
        }
        val movedByHand = seq.withIndex().filter { it.value !in unmoved }
        // many "moved" tiles means the guess does not hold; better no pins than a mess
        if (movedByHand.size in 1..12) Store.setPins(movedByHand.associate { it.value to it.index })
    }

    private fun matches(a: AppEntry, q: String) =
        label(a).lowercase().contains(q) ||
            a.sysLabel.lowercase().contains(q) ||
            a.pkg.lowercase().contains(q) ||
            a.genres.any { it.lowercase().contains(q) || genreLabel(it).lowercase().contains(q) }

    /**
     * Tried for a few minutes and left alone since (or already uninstalled).
     * The system counts play time only from a certain date on, so the guess is made only
     * for games installed after that date, never for ones the user completed or favors,
     * and the user can switch it off per game.
     */
    private fun isShort(a: AppEntry): Boolean {
        if (a.isSystem || a.playTime !in 1 until SHORT_PLAY_MS) return false
        if (a.pkg in notShort || a.pkg in done || a.pkg in favs) return false
        if (a.notInstalled) return true
        return System.currentTimeMillis() - a.lastUsed > DAY_MS && a.installed > Apps.statsSince
    }

    /**
     * On the headset and never started. Only said when it can be known: with access to the
     * usage statistics, and for apps installed after those statistics begin.
     */
    private fun isNever(a: AppEntry): Boolean {
        if (a.isSystem || a.notInstalled || !usageAccess) return false
        if (a.playTime > 0 || a.lastUsed > 0 || a.pkg in notNever) return false
        return Apps.statsSince > 0 && a.installed > Apps.statsSince
    }

    private fun hasMark(a: AppEntry, mark: String) = when (mark) {
        "new" -> a.fresh && a.pkg !in notNew
        "never" -> isNever(a)
        "demo" -> a.demo
        "short" -> isShort(a)
        "bad" -> a.pkg in bad
        "done" -> a.pkg in done
        else -> false
    }

    private fun effectiveSort(): String {
        val s = Store.sort
        return if (SORTS.none { it.first == s } || (s == "time" && !usageAccess)) "recent" else s
    }

    private fun refresh() {
        favs = Store.favorites()
        bad = Store.marked("bad")
        done = Store.marked("done")
        notShort = Store.marked("notshort")
        notNew = Store.marked("notnew")
        notNever = Store.marked("notnever")
        names = Store.names()
        hidden = Store.hidden()
        usageAccess = Apps.hasUsageAccess(this)
        val folderOf = Store.folderOf()
        val folders = Store.folders()
        if (tab.startsWith("f:") && tab.substring(2) !in folders) tab = "all"
        val q = query.lowercase()

        // the user's order covers everything except system apps and hidden ones
        val universe = all.filter { !it.isSystem && it.pkg !in hidden }
        if (loaded && !migrated) {
            migrated = true
            migrateOldOrder(universe)
        }
        val sort = effectiveSort()
        arranged = arrange(universe, sort)
        val ranks = HashMap<String, Int>()
        arranged.forEachIndexed { i, a -> ranks[a.pkg] = i }
        rank = ranks

        // 1. what the current tab holds; a search looks through everything, whatever tab is open
        val pool = all.filter { a ->
            val isHidden = a.pkg in hidden
            when {
                tab == "hidden" -> isHidden && (q.isEmpty() || matches(a, q))
                isHidden -> false
                q.isNotEmpty() -> matches(a, q)
                tab == "sys" -> a.isSystem
                tab == "fav" -> a.pkg in favs
                tab.startsWith("f:") -> folderOf[a.pkg] == tab.substring(2)
                a.isSystem -> false
                tab == "side" -> a.isSideloaded
                else -> true
            }
        }

        // 2. installed / not installed
        countAll = pool.size
        countYes = pool.count { !it.notInstalled }
        countNo = countAll - countYes
        val byInst = when (inst) {
            "yes" -> pool.filter { !it.notInstalled }
            "no" -> pool.filter { it.notInstalled }
            else -> pool
        }

        // 3. marks, then genres, counted on what is left
        val marks = LinkedHashMap<Mark, Int>()
        for (m in MARKS) {
            val n = byInst.count { hasMark(it, m.id) }
            if (n > 0 || mark == m.id) marks[m] = n
        }
        markCounts = marks
        val byMark = if (mark.isEmpty()) byInst else byInst.filter { hasMark(it, mark) }
        val genres = HashMap<String, Int>()
        for (a in byMark) for (g in genresOf(a)) genres[g] = (genres[g] ?: 0) + 1
        if (genre.isNotEmpty() && genre !in genres) genre = ""
        genreCounts = genres

        shown = byMark
            .filter { genre.isEmpty() || genre in genresOf(it) }
            .sortedWith(compareBy<AppEntry> { ranks[it.pkg] ?: Int.MAX_VALUE }.then(comparator(sort)))

        buildTabs(folders)
        buildFilterRow()
        buildGenreBox()
        buildSetupReminder()
        buildRunning()
        buildHero()
        buildWallpaper()

        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        empty.text = when {
            !loaded -> s(R.string.loading)
            q.isNotEmpty() -> s(R.string.nothing_found)
            tab == "fav" && pool.isEmpty() -> s(R.string.fav_empty)
            else -> s(R.string.empty_here)
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildTabs(folders: List<String>) {
        tabs.removeAllViews()
        fun add(id: String, title: String, ic: Ic? = null, tint: Int = Pal.ACCENT, onLong: (() -> Unit)? = null) {
            val t = ui.tab(title, tab == id, ic, tint) {
                tab = id
                refresh()
            }
            if (onLong != null) t.setOnLongClickListener { onLong(); true }
            tabs.addView(t)
        }
        add("all", s(R.string.tab_all))
        add("fav", s(R.string.tab_fav), Ic.STAR, Pal.GOLD)
        for (f in folders) add("f:$f", f, Ic.FOLDER) { showFolderMenu(f) }
        add("side", s(R.string.tab_side))
        add("sys", s(R.string.tab_sys))
        add("hidden", s(R.string.tab_hidden))
    }

    /** The genre box: filled, like every selected control, once a genre is chosen. */
    private fun buildGenreBox() {
        val on = genre.isNotEmpty()
        if (genreShownOn != on) {
            genreShownOn = on
            val normal = if (on) Pal.ACCENT else Pal.GLASS
            genreBox.setTextColor(if (on) Pal.DARK else Pal.TEXT)
            genreBox.setCompoundDrawablesRelative(
                null, null, ui.icon(Ic.CHEVRON, if (on) Pal.DARK else Pal.DIM, 16), null
            )
            genreBox.background = ui.round(normal, 20)
            ui.hover(genreBox, normal, if (on) Pal.ACCENT_HI else Pal.GLASS_HI, 20)
        }
        genreBox.text = genre.ifEmpty { s(R.string.all_genres) }
        genreBox.visibility = if (genreCounts.isEmpty() && genre.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The genres of what is on screen, most common first, as a list dropping from the box. */
    private fun showGenres() {
        val order = compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        val names = genreCounts.entries.sortedWith(order)
        val items = ArrayList<Kit.Item>()
        items.add(Kit.Item(null, s(R.string.all_genres), checked = genre.isEmpty()))
        for ((g, n) in names) items.add(Kit.Item(null, "$g · $n", checked = g == genre))
        ui.dropdown(genreBox, items) { i ->
            genre = if (i == 0) "" else names[i - 1].key
            refresh()
        }
    }

    /**
     * The bar under the tabs: installed / not installed as a switch that takes one click, then the
     * mark filter as a removable label. The funnel button (marks) shows only when there are marks.
     */
    private fun buildFilterRow() {
        val state = if (mark.isNotEmpty()) 2 else if (markCounts.isNotEmpty()) 1 else 0
        if (funnelState != state) {
            funnelState = state
            filterSlot.removeAllViews()
            if (state > 0) {
                filterSlot.addView(
                    ui.iconButton(Ic.FUNNEL, s(R.string.filters), active = state == 2) { showFilters() }
                )
            }
        }
        filterRow.removeAllViews()
        if (countNo > 0 || inst != "all") {
            val ids = listOf("all", "yes", "no")
            filterRow.addView(
                ui.segment(
                    listOf(
                        "${s(R.string.f_all)} · $countAll",
                        "${s(R.string.f_installed)} · $countYes",
                        "${s(R.string.f_not_installed)} · $countNo",
                    ),
                    ids.indexOf(inst).coerceAtLeast(0),
                    compact = true
                ) { i ->
                    setInstall(ids[i])
                    refresh()
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) }
            )
        }
        MARKS.firstOrNull { it.id == mark }?.let { m ->
            filterRow.addView(
                ui.chip(s(m.title), false, small = true, ic = Ic.CLOSE) {
                    mark = ""
                    refresh()
                }
            )
        }
        if (Owned.busy.isNotEmpty() && Owned.time == 0L && !Owned.denied) {
            filterRow.addView(ui.text(s(R.string.scanning), 13f, Pal.DIM).apply { setPadding(dp(4), dp(8), 0, dp(8)) })
        }
        filterScroll.visibility = if (filterRow.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun setInstall(id: String) {
        inst = id
        Store.installFilter = id
    }

    private fun showFilters() {
        ui.sheet(s(R.string.filters), 720) { body, d ->
            fun pick(change: () -> Unit) {
                change()
                d.dismiss()
                refresh()
            }
            if (mark.isNotEmpty()) {
                body.addView(
                    ui.button(s(R.string.f_reset), Ic.CLOSE, primary = false) { pick { mark = "" } },
                    LinearLayout.LayoutParams(MATCH, WRAP)
                )
            }

            if (markCounts.isNotEmpty()) {
                ui.section(body, s(R.string.f_marks))
                twoColumns(body, markCounts.map { (m, n) ->
                    ui.row(null, m.ic, s(m.title), value = n.toString(), tint = m.color, checked = mark == m.id) {
                        pick { mark = if (mark == m.id) "" else m.id }
                    }
                })
            }
        }
    }

    /** Lays rows out two to a line. */
    private fun twoColumns(body: LinearLayout, rows: List<Kit.Row>) {
        var line: LinearLayout? = null
        for ((i, r) in rows.withIndex()) {
            if (i % 2 == 0) {
                line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                body.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
            }
            line!!.addView(
                r.view,
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i % 2 == 0) rightMargin = dp(8) }
            )
        }
        if (rows.size % 2 == 1) line!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
    }

    private fun showFolderMenu(folder: String) {
        ui.list(
            folder,
            listOf(
                Kit.Item(Ic.EDIT, s(R.string.rename)),
                Kit.Item(Ic.TRASH, s(R.string.delete_folder), tint = Pal.RED),
            )
        ) { i ->
            if (i == 0) {
                prompt(s(R.string.folder_name), folder) { name ->
                    Store.renameFolder(folder, name)
                    if (tab == "f:$folder") tab = "f:$name"
                    refresh()
                }
            } else {
                // apps stay installed, only the grouping goes away
                Store.deleteFolder(folder)
                refresh()
            }
        }
    }

    // ------------------------------------------------------------ wallpaper

    /** The cover behind the whole screen: the game the user picked, else the one played most. */
    private fun buildWallpaper() {
        val games = all.filter { !it.isSystem && it.hasCover && it.pkg !in hidden }
        val chosen = if (!Store.wallpaperOn) null else {
            val own = Store.wallpaper
            games.firstOrNull { it.pkg == own }
                ?: games.filter { !it.notInstalled && it.playTime > 0 }.maxByOrNull { it.playTime }
                ?: games.filter { !it.notInstalled && it.lastUsed > 0 }.maxByOrNull { it.lastUsed }
        }
        val file = chosen?.let { Meta.coverFile(it.pkg) }?.takeIf { it.exists() }
        val key = if (chosen == null || file == null) "" else "${chosen.pkg}:${file.lastModified()}"
        if (key == wallKey) return
        wallKey = key
        if (file == null) {
            backdrop.setCover(null)
            return
        }
        // the tiles hold a reduced copy; the edges of the panel show the picture as it is, so read it in full
        thread {
            val full = try {
                android.graphics.BitmapFactory.decodeFile(file.path)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread { if (wallKey == key && !isDestroyed) backdrop.setCover(full) }
        }
    }

    // ----------------------------------------------------------------- hero

    private fun corners(v: View, radius: Int) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(radius).toFloat())
            }
        }
    }

    /** A drawable of its own for a second view: one drawable cannot sit in two places. */
    private fun copyOf(d: Drawable): Drawable = d.constantState?.newDrawable(resources)?.mutate() ?: d

    /**
     * Meta's menu offers to go back to the running game or to quit it. This launcher opens in
     * place of that menu, so it has to offer the same: a bar that is there for as long as a
     * game is running underneath.
     */
    private fun buildRunning() {
        val a = Apps.running?.takeIf { it != quitting }?.let { pkg ->
            all.firstOrNull { it.pkg == pkg && !it.notInstalled && !it.isSystem }
        }
        val key = if (a == null) "" else "${a.pkg}:${label(a)}:${a.hasCover}"
        if (key == runKey) return
        runKey = key
        runBox.removeAllViews()
        if (a == null) {
            runBox.visibility = View.GONE
            return
        }
        // The one thing on the screen that must not be missed: the game's own picture as the
        // background of the bar, and a green edge round it.
        val card = FrameLayout(this).apply { background = ui.round(0xFF1F3A2A.toInt(), 28) }
        corners(card, 28)
        val bitmap = (a.icon as? BitmapDrawable)?.bitmap
        if (a.hasCover && bitmap != null) {
            card.addView(
                ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (Build.VERSION.SDK_INT >= 31) {
                        val r = dp(6).toFloat()
                        setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                    }
                },
                FrameLayout.LayoutParams(MATCH, MATCH)
            )
            // darker on the left, where the words are
            card.addView(
                View(this).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(0xE6141416.toInt(), 0x99141416.toInt(), 0x59141416)
                    )
                },
                FrameLayout.LayoutParams(MATCH, MATCH)
            )
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(8), dp(12))
        }
        val cover = ImageView(this).apply {
            setImageDrawable(copyOf(a.icon))
            scaleType = if (a.hasCover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        ui.rounded(cover, 0.22f)
        row.addView(cover, LinearLayout.LayoutParams(dp(64), dp(64)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(12), 0)
        }
        texts.addView(ui.text(s(R.string.run_now).uppercase(), 12f, Pal.GREEN, bold = true).apply {
            letterSpacing = 0.1f
            ui.shadow(this)
            // a green dot before the words
            setCompoundDrawablesRelative(ui.oval(Pal.GREEN).apply { setBounds(0, 0, dp(9), dp(9)) }, null, null, null)
            compoundDrawablePadding = dp(8)
        })
        texts.addView(ui.text(label(a), 21f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            ui.shadow(this)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(ui.chip(s(R.string.run_back), true, ic = Ic.PLAY) { launch(a) })
        row.addView(ui.chip(s(R.string.run_quit), false, ic = Ic.CLOSE) { quitRunning(a) }.apply {
            // on the picture: its own solid grey, like the other controls of the main screen
            background = ui.round(Pal.GLASS, 24)
            ui.hover(this, Pal.GLASS, Pal.GLASS_HI, 24)
        })
        card.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(
            View(this).apply {
                background = GradientDrawable().apply {
                    setColor(0)
                    cornerRadius = dp(28).toFloat()
                    setStroke(dp(2), Pal.GREEN)
                }
            },
            FrameLayout.LayoutParams(MATCH, MATCH)
        )
        // a fixed height: the picture behind would otherwise stretch the bar to its own size
        runBox.addView(card, FrameLayout.LayoutParams(MATCH, dp(92)))
        runBox.visibility = View.VISIBLE
    }

    /**
     * Quits the running game the way the Quit button of Meta's menu does: the shell is asked
     * to go home, and it ends the immersive app on the way (seen in the system log; simply
     * starting the home screen from here does not end the game). If the game is still there
     * a moment later, Meta's menu is shown instead, with its own button.
     */
    private fun quitRunning(a: AppEntry) {
        android.util.Log.i("Shelf", "quitting ${a.pkg}")
        try {
            sendBroadcast(
                Intent("com.oculus.vrshell.intent.action.LAUNCH")
                    .setData(Uri.parse("com.oculus.vrshell/com.oculus.vrshell.HomeActivity"))
                    .setClassName("com.oculus.vrshell", "com.oculus.vrshell.ShellControlBroadcastReceiver")
            )
        } catch (e: Exception) {
            android.util.Log.w("Shelf", "could not ask the shell to go home", e)
        }
        // The bar goes at once: the game is gone within a second, while the system's record of
        // it lags behind. Whether it really went is checked in the background.
        quitting = a.pkg
        refresh()
        thread {
            var still = true
            for (i in 0 until 12) {
                Thread.sleep(350)
                still = Apps.refreshRunning(this) == a.pkg
                if (!still) break
            }
            runOnUiThread {
                quitting = null
                if (isDestroyed) return@runOnUiThread
                if (still) {
                    android.util.Log.i("Shelf", "${a.pkg} is still running: showing Meta's menu")
                    refresh()
                    toast(s(R.string.t_quit_failed))
                    links.firstOrNull { it.id == SystemLinks.LIBRARY }?.let { openLink(it) }
                } else {
                    android.util.Log.i("Shelf", "${a.pkg} was quit")
                    // back in front, in case the trip home put the launcher away
                    WatchService.openLauncher(applicationContext, "game quit")
                    reload()
                }
            }
        }
    }

    private fun buildHero() {
        val games = all.filter { !it.isSystem && !it.notInstalled && it.pkg !in hidden }
        val last = games.filter { it.lastUsed > 0 }.maxByOrNull { it.lastUsed }
        // while a game is running, its bar stands in for "continue"
        heroWanted = last != null && tab == "all" && query.isEmpty() && mark.isEmpty() && genre.isEmpty() &&
            runKey.isEmpty()
        if (last == null || !heroWanted) {
            heroBox.visibility = View.GONE
            return
        }
        // the most played strip needs room next to the main card
        val wide = resources.configuration.screenWidthDp >= 900
        val most = if (!wide) emptyList() else {
            games.filter { it.playTime > 0 && it.pkg != last.pkg }.sortedByDescending { it.playTime }.take(4)
        }
        val key = (listOf(last) + most).joinToString("|") {
            "${it.pkg}:${label(it)}:${it.hasCover}:${it.playTime / 60_000}:${it.lastUsed / 60_000}"
        }
        if (key != heroKey) {
            heroKey = key
            heroBox.removeAllViews()
            heroBox.addView(makeHero(last, most), FrameLayout.LayoutParams(MATCH, WRAP))
        }
        updateHeroVisibility()
    }

    private fun makeHero(last: AppEntry, most: List<AppEntry>): View {
        val height = dp(172)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // ---- the game to go on with: its cover, blurred, fills the card
        val card = FrameLayout(this).apply {
            background = ui.round(Pal.CARD, 28)
            isClickable = true
            isFocusable = true
            setOnClickListener { launch(last) }
        }
        corners(card, 28)
        val bitmap = (last.icon as? BitmapDrawable)?.bitmap
        if (last.hasCover && bitmap != null) {
            card.addView(
                ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    alpha = 0.6f
                    if (Build.VERSION.SDK_INT >= 31) {
                        val r = dp(14).toFloat()
                        setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                    }
                },
                FrameLayout.LayoutParams(MATCH, MATCH)
            )
        }
        card.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xF0202022.toInt(), 0xA6202022.toInt(), 0x4D202022)
                )
            },
            FrameLayout.LayoutParams(MATCH, MATCH)
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(24), 0)
        }
        val cover = ImageView(this).apply {
            setImageDrawable(copyOf(last.icon))
            scaleType = if (last.hasCover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(if (last.hasCover) 0 else Pal.CARD_HI)
        }
        ui.rounded(cover, 0.2f)
        content.addView(cover, LinearLayout.LayoutParams(dp(132), dp(132)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), 0, 0, 0)
        }
        texts.addView(ui.text(s(R.string.hero_continue).uppercase(), 12f, Pal.ACCENT_HI, bold = true).apply {
            letterSpacing = 0.12f
        })
        texts.addView(ui.text(label(last), 27f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(2))
        })
        val facts = ArrayList<String>()
        facts.addAll(genresOf(last).take(2))
        if (last.playTime > 0) facts.add(s(R.string.pl_time, duration(last.playTime)))
        if (facts.isNotEmpty()) {
            texts.addView(ui.text(facts.joinToString(" · "), 14f, 0xFFC8C8CC.toInt()).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        texts.addView(
            ui.button(s(R.string.hero_play), Ic.PLAY, primary = true) { launch(last) },
            LinearLayout.LayoutParams(WRAP, dp(48)).apply { topMargin = dp(14) }
        )
        content.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        row.addView(card, LinearLayout.LayoutParams(0, height, 1f))

        // ---- the games played most
        if (most.isNotEmpty()) {
            val side = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), 0, 0, 0)
            }
            side.addView(ui.text(s(R.string.hero_top).uppercase(), 12f, Pal.DIM, bold = true).apply {
                letterSpacing = 0.12f
                setPadding(dp(2), dp(2), 0, dp(8))
            })
            val covers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, a) in most.withIndex()) {
                val pic = ImageView(this).apply {
                    setImageDrawable(copyOf(a.icon))
                    scaleType = if (a.hasCover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(if (a.hasCover) 0 else Pal.CARD)
                }
                ui.rounded(pic, 0.2f)
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    isFocusable = true
                    addView(pic, LinearLayout.LayoutParams(dp(112), dp(112)))
                    addView(ui.text(duration(a.playTime), 12f, Pal.DIM).apply { setPadding(0, dp(6), 0, 0) })
                    setOnClickListener { launch(a) }
                    setOnHoverListener { _, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_HOVER_ENTER -> {
                                pic.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
                                ui.showTip(pic, label(a))
                            }
                            MotionEvent.ACTION_HOVER_EXIT -> {
                                pic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                                ui.hideTip()
                            }
                        }
                        false
                    }
                }
                covers.addView(item, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(12) })
            }
            side.addView(covers)
            row.addView(side, LinearLayout.LayoutParams(WRAP, height))
        }
        return row
    }

    /** The hero is there while the grid is at its top and steps aside once it is scrolled. */
    private fun updateHeroVisibility() {
        if (!heroWanted) {
            heroBox.visibility = View.GONE
            return
        }
        if (grid.isDragging) return
        val first = grid.getChildAt(0)
        val atTop = first == null || (grid.firstVisiblePosition == 0 && first.top >= 0)
        if (atTop) {
            if (heroBox.visibility != View.VISIBLE) heroBox.visibility = View.VISIBLE
            return
        }
        if (heroBox.visibility != View.VISIBLE) return
        // hide only when the list is long enough to stay scrolled once it gets the extra room,
        // otherwise it would snap back to the top and the hero would flicker
        val columns = maxOf(1, grid.numColumns)
        val rows = (shown.size + columns - 1) / columns
        if (rows * (first.height + grid.verticalSpacing) > grid.height + heroBox.height + dp(48)) {
            heroBox.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- tiles

    private fun applyTileSize() {
        grid.columnWidth = dp(Store.tile)
        // new adapter drops recycled tiles built for the old size
        grid.adapter = Tiles()
    }

    private inner class Tiles : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: makeTile()
            val h = v.tag as Holder
            val a = shown[position]
            h.icon.setImageDrawable(a.icon)
            // cover art fills the square; a plain APK icon sits on a tile-coloured plate
            h.icon.scaleType = if (a.hasCover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            h.icon.setBackgroundColor(if (a.hasCover) 0 else Pal.CARD)
            // a game that still has to be downloaded is drawn faded, with an arrow
            h.icon.alpha = if (a.notInstalled) 0.4f else 1f
            h.absent.visibility = if (a.notInstalled) View.VISIBLE else View.GONE
            h.label.text = label(a)
            h.label.setTextColor(if (a.notInstalled) Pal.DIM else Pal.TEXT)
            MARKS.forEachIndexed { i, m ->
                h.badges[i].visibility = if (hasMark(a, m.id)) View.VISIBLE else View.GONE
            }
            // a recycled tile must not keep the pointer highlight of the app it showed before
            if (h.pkg != a.pkg) h.hot = false
            h.pkg = a.pkg
            h.fav = a.pkg in favs
            h.more.tag = a.pkg
            h.star.tag = a.pkg
            h.star.setImageDrawable(
                if (h.fav) Icon(Ic.STAR, Pal.GOLD) else Icon(Ic.STAR_LINE, 0xE6FFFFFF.toInt())
            )
            h.star.background = ui.oval(if (h.fav) 0xE6000000.toInt() else 0x8C000000.toInt())
            paintHot(h, animate = false)
            // the tile being carried stays in place as a faint ghost
            v.alpha = if (a.pkg == moving) 0.25f else 1f
            return v
        }
    }

    /** The corner buttons show only under the pointer (the star also when it is set). */
    private fun paintHot(h: Holder, animate: Boolean) {
        h.more.alpha = if (h.hot) 1f else 0f
        h.star.alpha = if (h.hot || h.fav) 1f else 0f
        val scale = if (h.hot) 1.05f else 1f
        if (animate) {
            h.box.animate().scaleX(scale).scaleY(scale).setDuration(110).start()
        } else {
            h.box.animate().cancel()
            h.box.scaleX = scale
            h.box.scaleY = scale
        }
    }

    private fun makeTile(): View {
        val size = dp(Store.tile - 36)
        val icon = ImageView(this)
        ui.rounded(icon, 0.2f)

        /** Round button drawn over a corner of the cover. */
        fun corner(onClick: (String) -> Unit) = ImageView(this).apply {
            val p = dp(10)
            setPadding(p, p, p, p)
            // must not take focus, or the grid stops delivering clicks to the tile itself
            isFocusable = false
            setOnClickListener { v -> (v.tag as? String)?.let(onClick) }
        }
        /** Says on hover what a small sign on the cover means. */
        fun explain(v: View, title: String) = v.setOnHoverListener { x, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> ui.showTip(x, title)
                MotionEvent.ACTION_HOVER_EXIT -> ui.hideTip()
            }
            true
        }
        val star = corner { pkg ->
            Store.toggleFavorite(pkg)
            refresh()
        }
        explain(star, s(R.string.favorite))
        val more = corner { pkg ->
            all.firstOrNull { it.pkg == pkg }?.let { showDetails(it) }
        }.apply {
            setImageDrawable(Icon(Ic.MORE, 0xF2FFFFFF.toInt()))
            background = ui.oval(0x8C000000.toInt())
        }
        // one small disc per mark, shown as needed
        val badges = MARKS.map { m ->
            ImageView(this).apply {
                setImageDrawable(Icon(m.ic, Pal.DARK))
                val p = dp(5)
                setPadding(p, p, p, p)
                background = ui.oval(m.color)
                visibility = View.GONE
                explain(this, s(m.title))
            }
        }
        val badgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (b in badges) addView(b, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(4) })
        }
        val absent = ImageView(this).apply {
            setImageDrawable(Icon(Ic.DOWNLOAD, Pal.DARK))
            val p = dp(6)
            setPadding(p, p, p, p)
            background = ui.oval(Pal.ACCENT)
            visibility = View.GONE
            explain(this, s(R.string.tip_absent))
        }
        val box = FrameLayout(this).apply {
            addView(icon, FrameLayout.LayoutParams(size, size))
            addView(
                more,
                FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START).apply {
                    topMargin = dp(4)
                    leftMargin = dp(4)
                }
            )
            addView(
                star,
                FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(4)
                    rightMargin = dp(4)
                }
            )
            addView(
                badgeRow,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(7)
                    bottomMargin = dp(7)
                }
            )
            addView(
                absent,
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM or Gravity.END).apply {
                    rightMargin = dp(7)
                    bottomMargin = dp(7)
                }
            )
        }
        val label = ui.text("", 14f).apply {
            ui.shadow(this)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 2
            minLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val holder = Holder(box, icon, star, more, badges, absent, label)
        return HoverBox(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(12), dp(6), dp(6))
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // the cover grows a little under the pointer and must not be cut at the tile's edge
            clipChildren = false
            clipToPadding = false
            layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
            addView(box, LinearLayout.LayoutParams(size, size))
            addView(label, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            tag = holder
            onHot = { hot ->
                if (holder.hot != hot) {
                    holder.hot = hot
                    paintHot(holder, animate = true)
                }
            }
        }
    }

    // -------------------------------------------------------------- actions

    private fun launch(a: AppEntry) {
        val intent = a.launch
        if (intent == null) {
            // not on the headset: the store page is where it gets downloaded
            openStore(a)
            return
        }
        try {
            startActivity(intent)
            Store.markLaunched(a.pkg)
        } catch (e: Exception) {
            toast(s(R.string.t_launch_failed, e.message ?: ""))
        }
    }

    private fun openStore(a: AppEntry) {
        if (a.storeId.isEmpty()) {
            toast(s(R.string.t_no_store))
            return
        }
        val uri = Uri.parse("https://www.meta.com/experiences/${a.storeId}/")
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).setPackage("com.oculus.store").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            open(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openLink(link: SysLink) {
        val intent = link.intent
        if (link.id != SystemLinks.LIBRARY) {
            if (intent != null) open(intent, link.fallback)
            return
        }
        // Meta's library is the very window this launcher replaces: let it through once
        WatchService.allowStockLibrary()
        if (intent != null) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                android.util.Log.w("Shelf", "stock library cannot be started directly", e)
            }
        }
        // it cannot be started from here, but the next press of the Meta button will show it
        toast(s(R.string.t_press_meta))
        moveTaskToBack(true)
    }

    /** A tile was dropped so that it should stand at index [k] of what is on screen. */
    private fun dropAt(a: AppEntry, k: Int) {
        // the first tile at or after the drop point that takes part in the user's order
        var before = (k until shown.size).map { shown[it].pkg }.firstOrNull { it != a.pkg && it in rank }
        if (before == null) {
            // dropped behind the last such tile on screen: stand right after it in the full list
            val prev = (k - 1 downTo 0).map { shown[it].pkg }.firstOrNull { it != a.pkg && it in rank } ?: return
            val i = arranged.indexOfFirst { it.pkg == prev }
            before = arranged.drop(i + 1).firstOrNull { it.pkg != a.pkg }?.pkg
        }
        place(a.pkg, before)
    }

    /**
     * Puts [pkg] right before [before] (at the very end when null) and remembers that place.
     * Tiles placed by hand earlier keep the places they have at this moment.
     */
    private fun place(pkg: String, before: String?) {
        val order = arranged.map { it.pkg }.toMutableList()
        if (!order.remove(pkg)) return
        val at = before?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: order.size
        order.add(at, pkg)
        val byHand = Store.pins().keys + pkg
        val pins = HashMap<String, Int>()
        order.forEachIndexed { i, p -> if (p in byHand) pins[p] = i }
        Store.setPins(pins)
    }

    private fun duration(ms: Long): String {
        val min = (ms / 60_000).toInt()
        return when {
            min < 1 -> s(R.string.d_lt_min)
            min < 60 -> s(R.string.d_min, min)
            else -> s(R.string.d_h_min, min / 60, min % 60)
        }
    }

    private fun date(ms: Long) =
        SimpleDateFormat("dd.MM.yyyy", resources.configuration.locales[0]).format(Date(ms))

    /** The card behind the "..." button: what is known about the app and everything that can be done with it. */
    private fun showDetails(a: AppEntry) {
        ui.sheet(label(a), 700) { body, d ->
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pic = ImageView(this).apply {
                setImageDrawable(copyOf(a.icon))
                scaleType = if (a.hasCover) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(if (a.hasCover) 0 else Pal.CARD)
            }
            ui.rounded(pic, 0.2f)
            head.addView(pic, LinearLayout.LayoutParams(dp(112), dp(112)))

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), 0, 0, 0)
            }
            fun line(text: String, size: Float, color: Int) =
                info.addView(ui.text(text, size, color).apply { setPadding(0, dp(2), 0, dp(2)) })

            // what the launcher worked out by itself: new, never launched, demo, barely played
            val auto = MARKS.filter { it.id in setOf("new", "never", "demo", "short") && hasMark(a, it.id) }
            if (auto.isNotEmpty()) {
                val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (m in auto) {
                    val guess = m.id != "demo"
                    val pill = ui.text(s(m.title), 13f, m.color, bold = true).apply {
                        // "new" and "barely played" are guesses: the cross takes one off this game for good
                        setCompoundDrawablesRelative(
                            ui.icon(m.ic, m.color, 16), null, if (guess) ui.icon(Ic.CLOSE, m.color, 14) else null, null
                        )
                        compoundDrawablePadding = dp(6)
                        background = ui.round(Pal.wash(m.color), 12)
                        setPadding(dp(10), dp(5), dp(12), dp(5))
                    }
                    if (guess) {
                        pill.isClickable = true
                        pill.setOnClickListener {
                            Store.toggleMark("not${m.id}", a.pkg)
                            pill.visibility = View.GONE
                            refresh()
                        }
                    }
                    pills.addView(
                        pill,
                        LinearLayout.LayoutParams(WRAP, WRAP).apply {
                            rightMargin = dp(8)
                            bottomMargin = dp(6)
                        }
                    )
                }
                info.addView(pills)
            }
            val genres = genresOf(a)
            if (genres.isNotEmpty()) line(genres.joinToString(" · "), 15f, Pal.ACCENT_HI)
            line(
                when {
                    a.notInstalled && a.removed > 0 -> s(R.string.st_removed, date(a.removed))
                    a.notInstalled -> s(R.string.st_owned)
                    a.isSystem -> s(R.string.st_system)
                    a.isSideloaded -> s(R.string.st_side, date(a.installed))
                    else -> s(R.string.st_store, date(a.installed))
                },
                14f, Pal.DIM
            )
            line(
                when {
                    a.playTime > 0 && a.lastUsed > 0 ->
                        s(R.string.pl_time_last, duration(a.playTime), date(a.lastUsed))
                    a.playTime > 0 -> s(R.string.pl_time, duration(a.playTime))
                    a.lastUsed > 0 -> s(R.string.pl_last, date(a.lastUsed))
                    a.notInstalled -> s(R.string.pl_unknown_removed)
                    usageAccess -> s(R.string.pl_never)
                    else -> s(R.string.pl_unavailable)
                },
                14f, Pal.DIM
            )
            // so that a small number for an old favourite is not taken at face value
            if (!a.notInstalled && Apps.statsSince > 0) {
                line(s(R.string.pl_note, date(Apps.statsSince)), 12f, 0xFF8C8C90.toInt())
            }
            line(a.pkg, 12f, 0xFF747478.toInt())
            head.addView(info, LinearLayout.LayoutParams(0, WRAP, 1f))
            body.addView(head, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) })

            // the main thing to do with it
            body.addView(
                ui.button(
                    s(if (a.notInstalled) R.string.install else R.string.launch),
                    if (a.notInstalled) Ic.DOWNLOAD else Ic.PLAY,
                    primary = true
                ) {
                    d.dismiss()
                    launch(a)
                },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
            )

            // the user's own marks: switched right here, the card stays open
            fun markTile(on: Ic, off: Ic, title: String, color: Int, isOn: () -> Boolean, flip: () -> Unit): View {
                val img = ImageView(this)
                val text = ui.text(title, 13f)
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(78)
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    addView(img, LinearLayout.LayoutParams(dp(26), dp(26)))
                    addView(text, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
                    isClickable = true
                    isFocusable = true
                }
                fun paint(hot: Boolean) {
                    val active = isOn()
                    img.setImageDrawable(Icon(if (active) on else off, if (active) color else Pal.DIM))
                    text.setTextColor(if (active) Pal.TEXT else Pal.DIM)
                    tile.background = ui.round(
                        when {
                            active -> Pal.wash(color, if (hot) 0x55 else 0x3A)
                            hot -> Pal.CARD_HI
                            else -> Pal.CARD
                        },
                        18
                    )
                }
                tile.setOnClickListener {
                    flip()
                    refresh()
                    paint(true)
                }
                tile.setOnHoverListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_HOVER_ENTER -> paint(true)
                        MotionEvent.ACTION_HOVER_EXIT -> paint(false)
                    }
                    false
                }
                paint(false)
                return tile
            }
            val trio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val third = { last: Boolean ->
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (!last) rightMargin = dp(8) }
            }
            trio.addView(
                markTile(Ic.STAR, Ic.STAR_LINE, s(R.string.favorite), Pal.GOLD, { a.pkg in favs }) {
                    Store.toggleFavorite(a.pkg)
                },
                third(false)
            )
            trio.addView(
                markTile(Ic.TROPHY, Ic.TROPHY, s(R.string.mark_done), Pal.GOLD, { a.pkg in done }) {
                    Store.toggleMark("done", a.pkg)
                },
                third(false)
            )
            trio.addView(
                markTile(Ic.SAD, Ic.SAD, s(R.string.mark_bad), Pal.RED, { a.pkg in bad }) {
                    Store.toggleMark("bad", a.pkg)
                },
                third(true)
            )
            body.addView(trio, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })

            // everything else, two to a row
            val acts = ArrayList<Kit.Row>()
            fun act(ic: Ic, title: String, tint: Int = Pal.ACCENT, run: () -> Unit) {
                acts.add(ui.row(null, ic, title, tint = tint) {
                    d.dismiss()
                    run()
                })
            }
            if (!a.notInstalled && a.storeId.isNotEmpty()) act(Ic.BAG, s(R.string.store_page)) { openStore(a) }
            act(Ic.FOLDER, s(R.string.to_folder)) { chooseFolder(a) }
            if (a.pkg in rank) {
                act(Ic.MOVE, s(R.string.move_end)) {
                    place(a.pkg, null)
                    refresh()
                }
                if (a.pkg in Store.pins()) {
                    act(Ic.REFRESH, s(R.string.unpin)) {
                        Store.setPins(Store.pins() - a.pkg)
                        refresh()
                    }
                }
            }
            if (a.hasCover) {
                val isWall = Store.wallpaper == a.pkg
                act(Ic.IMAGE, s(if (isWall) R.string.act_wallpaper_auto else R.string.act_wallpaper)) {
                    Store.wallpaper = if (isWall) "" else a.pkg
                    Store.wallpaperOn = true
                    refresh()
                }
            }
            act(Ic.EDIT, s(R.string.rename)) {
                prompt(s(R.string.rename_title), label(a), allowEmpty = true) { name ->
                    val default = a.storeName.ifEmpty { a.sysLabel }
                    Store.setName(a.pkg, if (name == default) null else name)
                    refresh()
                }
            }
            if (a.pkg in hidden) {
                act(Ic.EYE, s(R.string.unhide)) {
                    Store.toggleHidden(a.pkg)
                    refresh()
                }
            } else {
                act(Ic.EYE_OFF, s(R.string.hide)) {
                    Store.toggleHidden(a.pkg)
                    refresh()
                }
            }
            if (!a.notInstalled) {
                act(Ic.INFO, s(R.string.app_info)) {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                }
                if (!a.isSystem) {
                    act(Ic.TRASH, s(R.string.uninstall), Pal.RED) {
                        open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
                    }
                }
            }
            twoColumns(body, acts)
        }
    }

    private fun chooseFolder(a: AppEntry) {
        val folders = Store.folders()
        val current = Store.folderOf()[a.pkg]
        val items = ArrayList<Kit.Item>()
        for (f in folders) items.add(Kit.Item(Ic.FOLDER, f, checked = f == current))
        items.add(Kit.Item(Ic.PLUS, s(R.string.new_folder)))
        if (current != null) items.add(Kit.Item(Ic.CLOSE, s(R.string.remove_from_folder), tint = Pal.RED))
        ui.list(s(R.string.folder_for, label(a)), items) { i ->
            when {
                i < folders.size -> Store.setFolder(a.pkg, folders[i])
                i == folders.size -> prompt(s(R.string.new_folder), "") { name ->
                    Store.addFolder(name)
                    Store.setFolder(a.pkg, name)
                    refresh()
                }
                else -> Store.setFolder(a.pkg, null)
            }
            refresh()
        }
    }

    private fun showSortMenu() {
        val current = effectiveSort()
        val offered = SORTS.filter { it.first != "time" || usageAccess }
        val items = ArrayList<Kit.Item>()
        for (o in offered) items.add(Kit.Item(null, s(o.second), checked = o.first == current))
        // tiles moved by hand stay put under any sort; this lets them go again
        val byHand = Store.pins().isNotEmpty()
        if (byHand) items.add(Kit.Item(Ic.REFRESH, s(R.string.reset_pins), tint = Pal.RED))
        ui.list(s(R.string.sort_title), items) { i ->
            if (i < offered.size) Store.sort = offered[i].first else Store.setPins(emptyMap())
            refresh()
        }
    }

    // ------------------------------------------------------------- settings

    // ---------------------------------------------------------------- setup

    /** Opens the first of these system screens that this headset has. */
    private fun openSystemScreen(vararg intents: Intent): Boolean {
        for (i in intents) {
            if (i.resolveActivity(packageManager) == null) continue
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                android.util.Log.w("Shelf", "could not open $i", e)
            }
        }
        return false
    }

    /**
     * A screen of Android's own settings app, asked for by name first. Horizon OS answers the
     * usual settings actions with Meta's settings, which have no such pages; the Android ones
     * are still on the headset and open when named directly.
     */
    private fun androidScreen(cls: String, action: String, data: Uri? = null): Array<Intent> = arrayOf(
        Intent(action, data).setClassName("com.android.settings", "com.android.settings.$cls"),
        Intent(action, data),
    )

    private fun accessibilityScreen() =
        androidScreen("Settings\$AccessibilitySettingsActivity", Settings.ACTION_ACCESSIBILITY_SETTINGS)

    private fun usageScreen() =
        androidScreen("Settings\$UsageAccessSettingsActivity", Settings.ACTION_USAGE_ACCESS_SETTINGS)

    private fun appDetails() = androidScreen(
        "applications.InstalledAppDetails",
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")
    )

    /** The one line on the main screen that says the launcher is not doing its main job yet. */
    private fun buildSetupReminder() {
        val needed = !serviceEnabled() && !Store.setupHushed
        val key = if (needed) "on" else ""
        if (key == setupKey) return
        setupKey = key
        setupBox.removeAllViews()
        if (!needed) {
            setupBox.visibility = View.GONE
            return
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.round(Pal.GLASS, 24)
            setPadding(dp(16), dp(8), dp(4), dp(8))
        }
        row.addView(
            ImageView(this).apply { setImageDrawable(Icon(Ic.INFO, Pal.GOLD)) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) }
        )
        row.addView(ui.text(s(R.string.setup_reminder), 15f), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(ui.chip(s(R.string.setup_open), true, small = true) { showSetup() })
        row.addView(ui.iconButton(Ic.CLOSE, s(R.string.setup_hide), size = 40, plain = true) {
            Store.setupHushed = true
            refresh()
        })
        setupBox.addView(row, FrameLayout.LayoutParams(MATCH, WRAP))
        setupBox.visibility = View.VISIBLE
    }

    /**
     * The guide that stands in for an install script: what the launcher needs from the system,
     * whether it has it, and the system screen where it is switched on. Shown by itself on the
     * first start, and again (rebuilt) every time the user comes back from one of those screens.
     */
    private fun showSetup() {
        setupDialog?.dismiss()
        setupDialog = ui.sheet(s(R.string.setup_title), 760) { body, _ ->
            body.addView(ui.text(s(R.string.setup_intro), 15f, Pal.DIM).apply {
                setPadding(dp(6), 0, dp(6), dp(10))
            })
            fun note(text: String) = body.addView(ui.text(text, 13f, Pal.DIM).apply {
                setPadding(dp(8), 0, dp(8), dp(10))
            })

            // ---- 1. the service: opening in place of Meta's library, autostart, back to the middle
            val svcOn = serviceEnabled()
            val svcOk = svcOn && WatchService.running
            ui.row(
                body, if (svcOk) Ic.OK_CIRCLE else Ic.SWAP, s(R.string.setup_service),
                sub = s(
                    when {
                        svcOk -> R.string.setup_service_on
                        svcOn -> R.string.svc_enabled_not_running
                        else -> R.string.setup_service_off
                    }
                ),
                value = if (svcOk) null else s(R.string.setup_go),
                tint = if (svcOk) Pal.GREEN else Pal.GOLD
            ) {
                if (!openSystemScreen(*accessibilityScreen())) toast(s(R.string.setup_no_screen))
            }
            if (!svcOk) {
                note(s(R.string.setup_service_hint, "Shelf"))
                ui.row(body, Ic.INFO, s(R.string.setup_restricted), sub = s(R.string.setup_restricted_sub)) {
                    if (!openSystemScreen(*appDetails())) toast(s(R.string.setup_no_screen))
                }
            }

            // ---- 2. usage statistics: "last played", play time, the running game
            val usageOk = Apps.hasUsageAccess(this)
            ui.row(
                body, if (usageOk) Ic.OK_CIRCLE else Ic.CHART, s(R.string.setup_usage),
                sub = s(if (usageOk) R.string.setup_usage_on else R.string.setup_usage_off),
                value = if (usageOk) null else s(R.string.setup_go),
                tint = if (usageOk) Pal.GREEN else Pal.GOLD
            ) {
                if (!openSystemScreen(*usageScreen())) toast(s(R.string.setup_no_screen))
            }

            // ---- 3. what goes over the network, said plainly, with its switch
            ui.toggle(body, Ic.DOWNLOAD, s(R.string.opt_meta), Store.fetchMeta) {
                Store.fetchMeta = it
                if (it) reload()
            }
            note(s(R.string.setup_net_note))
        }
    }

    private fun serviceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return s.split(':').any {
            val c = ComponentName.unflattenFromString(it)
            c != null && c.packageName == packageName && c.className == WatchService::class.java.name
        }
    }

    private fun tileSizeName() = s(
        when (Store.tile) {
            in 0..140 -> R.string.tile_small
            in 141..190 -> R.string.tile_medium
            else -> R.string.tile_large
        }
    )

    private fun showSettings() {
        ui.sheet(s(R.string.settings), 740) { body, d ->
            // ---- status
            ui.section(body, s(R.string.sec_status))
            val svcOn = serviceEnabled()
            val svcOk = svcOn && WatchService.running
            ui.row(
                body, Ic.OK_CIRCLE, s(R.string.set_service),
                sub = s(
                    when {
                        svcOk -> R.string.svc_running
                        svcOn -> R.string.svc_enabled_not_running
                        else -> R.string.svc_off
                    }
                ),
                tint = if (svcOk) Pal.GREEN else Pal.RED
            ) {
                d.dismiss()
                showSetup()
            }
            val usageOk = Apps.hasUsageAccess(this)
            ui.row(
                body, Ic.CHART, s(R.string.set_usage),
                sub = when {
                    !usageOk -> s(R.string.usage_no)
                    Apps.statsSince > 0 -> s(R.string.pl_note, date(Apps.statsSince))
                    else -> s(R.string.usage_ok)
                },
                tint = if (usageOk) Pal.GREEN else Pal.RED
            ) {
                d.dismiss()
                showSetup()
            }
            ui.row(
                body, Ic.BAG, s(R.string.set_owned),
                sub = when {
                    Owned.busy == "scan" -> s(R.string.busy_scan, Owned.busyCount)
                    Owned.busy == "catalog" -> s(R.string.busy_catalog, Owned.busyCount)
                    Owned.denied -> s(R.string.owned_denied)
                    Owned.time == 0L -> s(R.string.owned_never)
                    else -> s(
                        R.string.owned_status,
                        Owned.pkgs().size, Owned.total, all.count { it.notInstalled }, date(Owned.time)
                    )
                },
                tint = if (Owned.denied) Pal.RED else Pal.ACCENT
            )
            val storeApps = all.count { !it.isSystem && !it.isSideloaded && !it.notInstalled }
            val withCover = all.count { !it.isSystem && !it.isSideloaded && !it.notInstalled && it.hasCover }
            val pending = Meta.pending()
            ui.row(
                body, Ic.IMAGE, s(R.string.set_covers),
                sub = if (pending > 0) {
                    s(R.string.covers_pending, withCover, storeApps, pending)
                } else {
                    s(R.string.covers_status, withCover, storeApps)
                }
            )

            // ---- behavior
            ui.section(body, s(R.string.sec_behavior))
            ui.toggle(body, Ic.POWER, s(R.string.opt_boot), Store.autostartBoot) { Store.autostartBoot = it }
            ui.toggle(body, Ic.EYE, s(R.string.opt_wake), Store.autostartWake) { Store.autostartWake = it }
            ui.toggle(body, Ic.SWAP, s(R.string.opt_intercept), Store.intercept) { Store.intercept = it }
            ui.toggle(body, Ic.TARGET, s(R.string.opt_recenter), Store.recenter) { Store.recenter = it }
            ui.toggle(body, Ic.DOWNLOAD, s(R.string.opt_meta), Store.fetchMeta) {
                Store.fetchMeta = it
                if (it) reload()
            }

            // ---- appearance
            ui.section(body, s(R.string.sec_look))
            val langName = Lang.ALL.firstOrNull { it.first == Store.lang }?.second ?: s(R.string.lang_system)
            ui.row(body, Ic.GLOBE, s(R.string.set_language), value = langName) {
                d.dismiss()
                chooseLanguage()
            }
            ui.toggle(body, Ic.IMAGE, s(R.string.opt_wallpaper), Store.wallpaperOn) {
                Store.wallpaperOn = it
                refresh()
            }
            var size: Kit.Row? = null
            size = ui.row(body, Ic.GRID, s(R.string.set_tile), value = tileSizeName()) {
                Store.tile = when (Store.tile) {
                    in 0..140 -> 170
                    in 141..190 -> 220
                    else -> 130
                }
                applyTileSize()
                refresh()
                size?.value?.text = tileSizeName()
            }

            // ---- data
            ui.section(body, s(R.string.sec_data))
            ui.row(body, Ic.REFRESH, s(R.string.act_rescan)) {
                Owned.scan()
                toast(s(R.string.t_rescan))
                d.dismiss()
            }
            ui.row(body, Ic.IMAGE, s(R.string.act_retry_covers)) {
                Meta.forgetMisses()
                reload()
                toast(s(R.string.t_retrying))
                d.dismiss()
            }
            ui.row(
                body, Ic.DOWNLOAD, s(R.string.act_catalog),
                sub = if (Owned.catalogTime() > 0) {
                    s(R.string.catalog_sub_date, date(Owned.catalogTime()))
                } else {
                    s(R.string.catalog_sub)
                }
            ) {
                Owned.refreshCatalog()
                toast(s(R.string.t_catalog))
                d.dismiss()
            }

            // ---- advanced
            ui.section(body, s(R.string.sec_advanced))
            ui.row(body, Ic.TARGET, s(R.string.act_learn), sub = s(R.string.learn_sub)) {
                d.dismiss()
                startLearning()
            }
            ui.row(body, Ic.LIST, s(R.string.act_triggers), value = Store.triggers().size.toString()) {
                d.dismiss()
                showTriggers()
            }
            ui.row(body, Ic.LIST, s(R.string.act_log)) {
                d.dismiss()
                showEventLog()
            }
            links.firstOrNull { it.id == SystemLinks.LIBRARY }?.let { library ->
                ui.row(body, Ic.GRID, s(R.string.sys_library)) {
                    d.dismiss()
                    openLink(library)
                }
            }
            ui.row(body, Ic.OPEN, s(R.string.act_a11y)) {
                if (!openSystemScreen(*accessibilityScreen())) toast(s(R.string.setup_no_screen))
            }
        }
    }

    private fun chooseLanguage() {
        val current = Store.lang
        val items = ArrayList<Kit.Item>()
        items.add(Kit.Item(Ic.GLOBE, s(R.string.lang_system), checked = current.isEmpty()))
        for ((code, name) in Lang.ALL) items.add(Kit.Item(Ic.GLOBE, name, checked = code == current))
        ui.list(s(R.string.set_language), items) { i ->
            val picked = if (i == 0) "" else Lang.ALL[i - 1].first
            if (picked != current) {
                Store.lang = picked
                // rebuilds the whole screen with the other language's texts
                recreate()
            }
        }
    }

    private fun describe(t: Trigger): Kit.Item {
        val kind = s(
            when (t.type) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> R.string.ev_window
                AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> R.string.ev_hover
                AccessibilityEvent.TYPE_VIEW_CLICKED -> R.string.ev_click
                else -> R.string.ev_other
            }
        )
        val title = if (t.text.isEmpty()) kind else "$kind · ${t.text}"
        val sub = if (t.cls.isEmpty()) t.pkg else "${t.pkg} · ${t.cls.substringAfterLast('.')}"
        return Kit.Item(Ic.TARGET, title, sub = sub)
    }

    private fun startLearning() {
        if (!WatchService.running) {
            toast(s(R.string.t_no_service))
            return
        }
        WatchService.beginLearning(10_000)
        toast(s(R.string.t_learn))
        handler.postDelayed({ pickTrigger(s(R.string.learn_pick), WatchService.takeCaptured().asReversed()) }, 10_500)
    }

    private fun showEventLog() {
        if (!WatchService.running) {
            toast(s(R.string.t_log_empty))
            return
        }
        pickTrigger(s(R.string.log_title), WatchService.recent())
    }

    private fun pickTrigger(title: String, items: List<Trigger>) {
        if (isFinishing || isDestroyed) return
        if (items.isEmpty()) {
            toast(s(R.string.t_no_events))
            return
        }
        ui.list(title, items.map { describe(it) }) { i ->
            val t = items[i]
            if (t.tooBroad()) {
                toast(s(R.string.t_too_broad))
            } else {
                Store.addTrigger(t)
                Store.intercept = true
                toast(s(R.string.t_trigger_saved))
            }
        }
    }

    private fun showTriggers() {
        val items = Store.triggers()
        if (items.isEmpty()) {
            toast(s(R.string.t_no_triggers))
            return
        }
        ui.list(s(R.string.triggers_title), items.map { describe(it) }) { i ->
            Store.setTriggers(items.filterIndexed { n, _ -> n != i })
            toast(s(R.string.t_trigger_deleted))
        }
    }

    // -------------------------------------------------------------- helpers

    private fun prompt(title: String, initial: String, allowEmpty: Boolean = false, ok: (String) -> Unit) {
        ui.prompt(title, initial, allowEmpty, s(R.string.ok), s(R.string.cancel), ok)
    }

    private fun open(i: Intent, fallback: Intent? = null) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            android.util.Log.w("Shelf", "could not start $i", e)
            if (fallback != null) open(fallback) else toast(s(R.string.t_open_failed, e.message ?: ""))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
