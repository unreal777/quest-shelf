package app.shelf

import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject

/** One accessibility event signature that should open the launcher. */
data class Trigger(val type: Int, val pkg: String, val cls: String, val text: String) {

    fun key() = "$type|$pkg|$cls|$text"

    fun matches(e: Trigger) =
        type == e.type && pkg == e.pkg &&
            (cls.isEmpty() || cls == e.cls) &&
            (text.isEmpty() || text == e.text)

    /** Hover/click without any text would match half of the system UI. */
    fun tooBroad() = type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && text.isEmpty()

}

/** All persistent state. Small enough for SharedPreferences + JSON. */
object Store {
    private lateinit var p: SharedPreferences

    @Volatile
    private var trigCache: List<Trigger>? = null

    fun init(c: Context) {
        if (!::p.isInitialized) {
            p = c.applicationContext.getSharedPreferences("shelf", Context.MODE_PRIVATE)
        }
    }

    var sort: String
        get() = p.getString("sort", "recent") ?: "recent"
        set(v) = p.edit().putString("sort", v).apply()

    /** Tile column width in dp. */
    var tile: Int
        get() = p.getInt("tile", 170)
        set(v) = p.edit().putInt("tile", v).apply()

    var autostartBoot: Boolean
        get() = p.getBoolean("autostartBoot", true)
        set(v) = p.edit().putBoolean("autostartBoot", v).apply()

    var autostartWake: Boolean
        get() = p.getBoolean("autostartWake", false)
        set(v) = p.edit().putBoolean("autostartWake", v).apply()

    /** The first-run guide has been shown once. */
    var setupSeen: Boolean
        get() = p.getBoolean("setupSeen", false)
        set(v) = p.edit().putBoolean("setupSeen", v).apply()

    /** The user closed the "not set up yet" reminder on the main screen. */
    var setupHushed: Boolean
        get() = p.getBoolean("setupHushed", false)
        set(v) = p.edit().putBoolean("setupHushed", v).apply()

    var intercept: Boolean
        get() = p.getBoolean("intercept", true)
        set(v) = p.edit().putBoolean("intercept", v).apply()

    /** Replacing Meta's library is paused until this moment (0 = not paused). */
    var pauseUntil: Long
        get() = p.getLong("pauseUntil", 0L)
        set(v) = p.edit().putLong("pauseUntil", v).apply()

    /** The pause was started by the launcher itself because the replacement kept misfiring. */
    var pausedBySafeMode: Boolean
        get() = p.getBoolean("pausedBySafeMode", false)
        set(v) = p.edit().putBoolean("pausedBySafeMode", v).apply()

    fun paused() = pauseUntil > System.currentTimeMillis()

    /** Whether the stock library is to be replaced right now: switched on and not paused. */
    fun interceptNow() = intercept && !paused()

    /** Look up names, genres and covers of store apps online. */
    var fetchMeta: Boolean
        get() = p.getBoolean("fetchMeta", true)
        set(v) = p.edit().putBoolean("fetchMeta", v).apply()

    /** Whether a blurred game cover is drawn behind the whole screen. */
    /** Ask the system to put the launcher back in the middle once other windows are closed. */
    var recenter: Boolean
        get() = p.getBoolean("recenter", true)
        set(v) = p.edit().putBoolean("recenter", v).apply()

    var wallpaperOn: Boolean
        get() = p.getBoolean("wallpaperOn", true)
        set(v) = p.edit().putBoolean("wallpaperOn", v).apply()

    /** Package whose cover is the background, "" = the most played game. */
    var wallpaper: String
        get() = p.getString("wallpaper", "") ?: ""
        set(v) = p.edit().putString("wallpaper", v).apply()

    /** UI language code, "" = follow the headset. */
    var lang: String
        get() = p.getString("lang", "") ?: ""
        set(v) = p.edit().putString("lang", v).apply()

    /** A game that has not been played for this many days is "forgotten". */
    var idleDays: Int
        get() = p.getInt("idleDays", 30)
        set(v) = p.edit().putInt("idleDays", v).apply()

    /** all | yes (installed only) | no (not installed only) */
    var installFilter: String
        get() = p.getString("installFilter", "all") ?: "all"
        set(v) = p.edit().putString("installFilter", v).apply()

    /** Window title of the stock library as last seen on this headset (localized). */
    @Volatile
    private var libraryTitleCache: String? = null
    var libraryTitle: String
        get() = libraryTitleCache ?: (p.getString("libraryTitle", "") ?: "").also { libraryTitleCache = it }
        set(v) {
            libraryTitleCache = v
            p.edit().putString("libraryTitle", v).apply()
        }

    // ---- sets -------------------------------------------------------------

    fun favorites(): Set<String> = HashSet(p.getStringSet("fav", emptySet()) ?: emptySet())
    fun hidden(): Set<String> = HashSet(p.getStringSet("hidden", emptySet()) ?: emptySet())

    /** The user's own marks: "bad" = did not like it, "done" = finished. */
    fun marked(mark: String): Set<String> = HashSet(p.getStringSet("mark_$mark", emptySet()) ?: emptySet())

    fun toggleFavorite(pkg: String) = toggle("fav", pkg)
    fun toggleHidden(pkg: String) = toggle("hidden", pkg)
    fun toggleMark(mark: String, pkg: String) = toggle("mark_$mark", pkg)

    private fun toggle(key: String, pkg: String) {
        val s = HashSet(p.getStringSet(key, emptySet()) ?: emptySet())
        if (!s.remove(pkg)) s.add(pkg)
        p.edit().putStringSet(key, s).apply()
    }

    // ---- folders ----------------------------------------------------------

    fun folders(): List<String> = list("folders")

    fun addFolder(name: String) {
        val l = folders().toMutableList()
        if (name !in l) {
            l.add(name)
            putList("folders", l)
        }
    }

    fun renameFolder(old: String, new: String) {
        if (old == new || new in folders()) return
        putList("folders", folders().map { if (it == old) new else it })
        putMap("folderOf", folderOf().mapValues { if (it.value == old) new else it.value })
    }

    fun deleteFolder(name: String) {
        putList("folders", folders().filter { it != name })
        putMap("folderOf", folderOf().filterValues { it != name })
    }

    /** package -> folder name */
    fun folderOf(): Map<String, String> = map("folderOf")

    fun setFolder(pkg: String, folder: String?) {
        val m = folderOf().toMutableMap()
        if (folder == null) m.remove(pkg) else m[pkg] = folder
        putMap("folderOf", m)
    }

    // ---- custom names, order, launch history ------------------------------

    fun names(): Map<String, String> = map("names")

    fun setName(pkg: String, name: String?) {
        val m = names().toMutableMap()
        if (name.isNullOrBlank()) m.remove(pkg) else m[pkg] = name
        putMap("names", m)
    }

    /** The frozen full order of the old "custom order" sort; read once to carry it over. */
    fun oldOrder(): List<String> = list("order")
    fun dropOldOrder() = p.edit().remove("order").apply()

    /**
     * Tiles the user has put somewhere by hand: package -> place in the list.
     * Everything else follows the chosen sort and flows around them.
     */
    fun pins(): Map<String, Int> = map("pins").mapValues { it.value.toIntOrNull() ?: 0 }

    fun setPins(m: Map<String, Int>) = putMap("pins", m.mapValues { it.value.toString() })

    fun launched(): Map<String, Long> =
        map("launched").mapValues { it.value.toLongOrNull() ?: 0L }

    fun markLaunched(pkg: String) {
        val m = map("launched").toMutableMap()
        m[pkg] = System.currentTimeMillis().toString()
        putMap("launched", m)
    }

    // ---- play history ------------------------------------------------------

    /**
     * What is remembered about an app after it is gone: the system forgets the play time of
     * an app the moment it is uninstalled, so the launcher keeps its own copy.
     */
    class Hist(val play: Long, val used: Long, val removed: Long)

    fun history(): MutableMap<String, Hist> {
        val out = HashMap<String, Hist>()
        try {
            val o = JSONObject(p.getString("hist", "{}") ?: "{}")
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                out[k] = Hist(a.getLong(0), a.getLong(1), a.getLong(2))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun setHistory(m: Map<String, Hist>) {
        val o = JSONObject()
        for ((k, h) in m) o.put(k, JSONArray().put(h.play).put(h.used).put(h.removed))
        p.edit().putString("hist", o.toString()).apply()
    }

    // ---- triggers ---------------------------------------------------------

    /**
     * The stock library window, as observed on Quest 3 / Horizon OS v209 (Navigator UI).
     * Matched by package + activity class only, so it does not depend on the UI language.
     */
    val STOCK_LIBRARY = Trigger(
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        "com.oculus.panelapp.library",
        "com.oculus.navigator.library.app.NavigatorLibraryActivity",
        "",
    )

    fun triggers(): List<Trigger> {
        trigCache?.let { return it }
        if (!p.contains("triggers")) {
            val d = listOf(STOCK_LIBRARY)
            trigCache = d
            return d
        }
        val out = ArrayList<Trigger>()
        try {
            val a = JSONArray(p.getString("triggers", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Trigger(o.getInt("type"), o.getString("pkg"), o.optString("cls"), o.optString("text")))
            }
        } catch (_: Exception) {
        }
        trigCache = out
        return out
    }

    fun setTriggers(l: List<Trigger>) {
        val a = JSONArray()
        for (t in l) {
            a.put(JSONObject().put("type", t.type).put("pkg", t.pkg).put("cls", t.cls).put("text", t.text))
        }
        p.edit().putString("triggers", a.toString()).apply()
        trigCache = ArrayList(l)
    }

    fun addTrigger(t: Trigger) {
        val l = triggers()
        if (l.none { it.key() == t.key() }) setTriggers(l + t)
    }

    // ---- backup -----------------------------------------------------------

    /** Settings that belong to this headset or this moment, not to the user: left out of a backup. */
    private val NOT_BACKED_UP = setOf(
        "setupSeen", "setupHushed", "libraryTitle", "triggers", "pauseUntil", "pausedBySafeMode",
    )

    /** Everything the user has set up (favorites, folders, order, marks, options) as one JSON text. */
    fun exportJson(): String {
        val values = JSONObject()
        for ((k, v) in p.all) {
            if (k in NOT_BACKED_UP) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            values.put(k, o)
        }
        return JSONObject().put("app", "shelf").put("format", 1).put("values", values).toString(1)
    }

    /** Puts a backup made by [exportJson] back. False when the text is not one of ours. */
    fun importJson(text: String): Boolean {
        val values = try {
            val root = JSONObject(text)
            if (root.optString("app") != "shelf" || root.optInt("format", 0) != 1) return false
            root.getJSONObject("values")
        } catch (_: Exception) {
            return false
        }
        val e = p.edit()
        try {
            for (k in values.keys()) {
                if (k in NOT_BACKED_UP) continue
                val o = values.getJSONObject(k)
                when (o.getString("t")) {
                    "b" -> e.putBoolean(k, o.getBoolean("v"))
                    "i" -> e.putInt(k, o.getInt("v"))
                    "l" -> e.putLong(k, o.getLong("v"))
                    "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> e.putString(k, o.getString("v"))
                    "set" -> {
                        val a = o.getJSONArray("v")
                        e.putStringSet(k, HashSet<String>().also { s -> for (i in 0 until a.length()) s.add(a.getString(i)) })
                    }
                }
            }
        } catch (_: Exception) {
            return false
        }
        e.apply()
        return true
    }

    // ---- json helpers -----------------------------------------------------

    private fun list(key: String): List<String> {
        val out = ArrayList<String>()
        try {
            val a = JSONArray(p.getString(key, "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (_: Exception) {
        }
        return out
    }

    private fun putList(key: String, l: List<String>) =
        p.edit().putString(key, JSONArray(l).toString()).apply()

    private fun map(key: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val o = JSONObject(p.getString(key, "{}") ?: "{}")
            for (k in o.keys()) out[k] = o.getString(k)
        } catch (_: Exception) {
        }
        return out
    }

    private fun putMap(key: String, m: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        p.edit().putString(key, o.toString()).apply()
    }
}
