package app.shelf

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Store names, genres and cover art for apps installed from the Meta store.
 *
 * The headset keeps this data in a catalog that third-party apps cannot read, so it is
 * looked up by package name in the public OculusDB (name, genres, store id, cover), with
 * the open LauncherIcons archive as a fallback for the cover. Results are cached on disk;
 * every package is asked for once. Sideloaded apps are never looked up.
 */
object Meta {
    private const val TAG = "Shelf"
    private const val API = "https://oculusdb.rui2015.me/api/v1/packagename/"
    private const val SEARCH = "https://oculusdb.rui2015.me/api/v1/search/"
    private const val COVER = "https://oculusdb.rui2015.me/cdn/images/"
    private const val FALLBACK = "https://raw.githubusercontent.com/basti564/LauncherIcons/main/oculus_icon/"
    private const val RETRY_NOT_FOUND_MS = 7L * 24 * 3600 * 1000

    /** Bumped when the lookup gets smarter, so old "not found" answers are asked again. */
    private const val VERSION = 4

    class Info(
        val name: String,
        val genres: List<String>,
        val storeId: String,
        val found: Boolean,
        val time: Long,
        val ver: Int,
    )

    private lateinit var p: SharedPreferences
    private lateinit var coverDir: File
    private val map = ConcurrentHashMap<String, Info>()
    private val queued = Collections.synchronizedSet(HashSet<String>())
    private val coverTried = Collections.synchronizedSet(HashSet<String>())
    private val pool = Executors.newSingleThreadExecutor()
    private val coverPool = Executors.newFixedThreadPool(3)

    /** Called (from a background thread) after an app's data or cover has been stored. */
    @Volatile
    var onUpdate: (() -> Unit)? = null

    @Synchronized
    fun init(c: Context) {
        if (::p.isInitialized) return
        val app = c.applicationContext
        coverDir = File(app.filesDir, "covers").apply { mkdirs() }
        p = app.getSharedPreferences("meta", Context.MODE_PRIVATE)
        for ((pkg, raw) in p.all) {
            try {
                val o = JSONObject(raw as String)
                val g = ArrayList<String>()
                val a = o.optJSONArray("g")
                if (a != null) for (i in 0 until a.length()) g.add(a.getString(i))
                map[pkg] = Info(
                    o.optString("n"), g, o.optString("id"), o.optBoolean("ok"), o.optLong("t"), o.optInt("v", 1)
                )
            } catch (_: Exception) {
            }
        }
    }

    fun get(pkg: String): Info? = map[pkg]

    fun coverFile(pkg: String) = File(coverDir, "$pkg.img")

    /** (apps with data, apps asked about) */
    fun stats(): Pair<Int, Int> = Pair(map.values.count { it.found }, map.size)

    fun pending(): Int = queued.size

    /** Meta's own components (help, environments…) are not games and are not in the catalog. */
    fun isPlatformPackage(pkg: String) = pkg.startsWith("com.oculus.") || pkg.startsWith("com.meta.")

    /** Queues lookups for apps (package -> label) that have no cached answer yet. */
    fun request(apps: Map<String, String>) {
        val now = System.currentTimeMillis()
        for ((pkg, label) in apps) {
            val known = map[pkg]
            if (known != null &&
                (known.found || (known.ver >= VERSION && now - known.time < RETRY_NOT_FOUND_MS))
            ) continue
            if (!queued.add(pkg)) continue
            pool.execute {
                try {
                    fetch(pkg, label)
                } finally {
                    queued.remove(pkg)
                }
            }
        }
    }

    /** Forget "not found" answers and failed cover downloads so the next load retries them. */
    fun forgetMisses() {
        val e = p.edit()
        for ((pkg, info) in map.entries.toList()) {
            if (!info.found) {
                map.remove(pkg)
                e.remove(pkg)
            }
        }
        e.apply()
        coverTried.clear()
    }

    private fun fetch(pkg: String, label: String) {
        val now = System.currentTimeMillis()
        val file = coverFile(pkg)
        var info = Info("", emptyList(), "", false, now, VERSION)
        try {
            val (code, body) = httpGet(API + pkg)
            var o: JSONObject? = if (code == 200 && body != null) JSONObject(body) else null
            // some catalog entries carry no package name: find those by the app's title
            if (o == null && code == 404 && !isPlatformPackage(pkg)) o = searchByName(label, pkg)
            if (o != null) {
                val name = o.optString("displayName").ifBlank { o.optString("appName") }
                val genres = ArrayList<String>()
                val a = o.optJSONArray("genre_names")
                if (a != null) for (i in 0 until a.length()) genres.add(a.getString(i))
                val id = o.optString("id")
                info = Info(name, genres, id, name.isNotBlank(), now, VERSION)
                if (id.isNotBlank() && !file.exists()) download(COVER + id, file)
            } else if (code != 404) {
                // server trouble: do not remember this as "no such app"
                Log.w(TAG, "meta $pkg: http $code")
                return
            }
            if (!file.exists()) download(FALLBACK + pkg + ".jpg", file)
        } catch (e: Exception) {
            // offline or timeout: try again on the next launch
            Log.w(TAG, "meta $pkg: $e")
            return
        }
        save(pkg, info)
        onUpdate?.invoke()
    }

    private fun save(pkg: String, info: Info) {
        map[pkg] = info
        p.edit().putString(
            pkg,
            JSONObject()
                .put("n", info.name)
                .put("g", JSONArray(info.genres))
                .put("id", info.storeId)
                .put("ok", info.found)
                .put("t", info.time)
                .put("v", info.ver)
                .toString()
        ).apply()
    }

    /** Catalog data that is already known (from the bundled index): no network needed. */
    fun put(pkg: String, name: String, genres: List<String>, id: String) {
        if (map[pkg]?.found == true) return
        save(pkg, Info(name, genres, id, true, System.currentTimeMillis(), VERSION))
    }

    /** Queues cover downloads for apps whose store id is known but whose cover is missing. */
    fun requestCovers(pkgs: Collection<String>) {
        for (pkg in pkgs) {
            val id = map[pkg]?.storeId ?: continue
            val file = coverFile(pkg)
            // one attempt per app run: a missing cover must not be asked for on every redraw
            if (file.exists() || !coverTried.add(pkg) || !queued.add("cover:$pkg")) continue
            coverPool.execute {
                try {
                    if (id.isNotBlank()) download(COVER + id, file)
                    if (!file.exists()) download(FALLBACK + pkg + ".jpg", file)
                    if (file.exists()) onUpdate?.invoke()
                } catch (e: Exception) {
                    Log.w(TAG, "cover $pkg: $e")
                } finally {
                    queued.remove("cover:$pkg")
                }
            }
        }
    }

    private val DEMO = Regex("\\bdemo\\b", RegexOption.IGNORE_CASE)

    /** The store has no "demo" flag; demos say so in their name ("Beat Saber - Demo"). */
    fun isDemo(name: String) = DEMO.containsMatchIn(name)

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** "BattleTalent" -> "Battle Talent", "Wrath2" -> "Wrath 2", "Orcs Hands ON!" -> "Orcs Hands ON". */
    private fun spaced(s: String) = s
        .replace(Regex("([a-z])([A-Z0-9])"), "$1 $2")
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * APK labels are often internal build names, so try a few spellings: the label as is,
     * the label split into words, and the last part of the package name split into words.
     */
    private fun searchByName(label: String, pkg: String): JSONObject? {
        val queries = listOf(label, spaced(label), spaced(pkg.substringAfterLast('.')))
            .filter { it.length >= 3 }
            .distinct()
        for (q in queries) {
            val want = norm(q)
            val (code, body) = httpGet(SEARCH + URLEncoder.encode(q, "UTF-8").replace("+", "%20"))
            if (code != 200 || body == null) continue
            val results = JSONArray(body)
            val partial = ArrayList<JSONObject>()
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                if (r.optString("__OculusDBType") != "Application") continue
                val p = r.optString("packageName")
                if (p.isNotEmpty() && p != pkg) continue
                val n = norm(r.optString("displayName"))
                if (n.isEmpty()) continue
                if (n == want) return r
                if (want.length >= 6 && n.contains(want)) partial.add(r)
            }
            // "Wrath 2" inside "Asgard's Wrath 2": accept only when there is no other candidate
            if (partial.map { it.optString("id") }.distinct().size == 1) return partial[0]
            Log.d(TAG, "meta search \"$q\" ($pkg): ${results.length()} results, no confident match")
        }
        return null
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "QuestShelf-launcher")
        return c
    }

    private fun httpGet(url: String): Pair<Int, String?> {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code != 200) return Pair(code, null)
            return Pair(code, c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    /** Saves an image to [file]; leaves nothing behind unless it really decodes as an image. */
    private fun download(url: String, file: File) {
        val c = open(url)
        val tmp = File(file.path + ".tmp")
        try {
            if (c.responseCode != 200) return
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth > 0 && o.outHeight > 0) tmp.renameTo(file)
        } finally {
            tmp.delete()
            c.disconnect()
        }
    }
}
