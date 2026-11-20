package app.shelf

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which store apps the account owns, including the ones that are not installed.
 *
 * The headset does not hand out its library as a list, but it does answer the question
 * "is this package in the library?" for one package at a time. So every package of the
 * store catalog (a compact index bundled with the app, refreshable from OculusDB) is asked
 * about in turn. Everything stays on the headset: nothing about the library is sent anywhere.
 */
object Owned {
    private const val TAG = "Shelf"
    private val BASE: Uri = Uri.parse("content://com.oculus.ocms.publiclibrary/apps")
    private const val ALL_APPS = "https://oculusdb.rui2015.me/api/v1/allapps"
    private const val CATALOG = "catalog.tsv"
    private const val EXTRA = "catalog_extra.tsv"
    private const val RESCAN_MS = 12L * 3600 * 1000

    private lateinit var app: Context
    private lateinit var p: SharedPreferences
    private val pool = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)

    @Volatile
    private var cache: Set<String>? = null

    /** What the background job is doing right now: "scan", "catalog", or "" when idle. */
    @Volatile
    var busy = ""
        private set

    /** How many catalog entries the running job has gone through. */
    @Volatile
    var busyCount = 0
        private set

    /** Called (from a background thread) when the owned list has changed. */
    @Volatile
    var onUpdate: (() -> Unit)? = null

    @Synchronized
    fun init(c: Context) {
        if (::p.isInitialized) return
        app = c.applicationContext
        p = app.getSharedPreferences("owned", Context.MODE_PRIVATE)
    }

    fun pkgs(): Set<String> =
        cache ?: HashSet(p.getStringSet("pkgs", emptySet()) ?: emptySet()).also { cache = it }

    /**
     * Packages that joined the library after the launcher first read it. What was bought and
     * removed before that cannot be told apart from what was never installed, so only later
     * arrivals count.
     */
    fun fresh(): Set<String> = HashSet(p.getStringSet("fresh", emptySet()) ?: emptySet())

    /** When the library was last checked, 0 = never. */
    val time: Long get() = p.getLong("time", 0)

    /** How many entries the system library holds in total (installed ones included), -1 = unknown. */
    val total: Int get() = p.getInt("total", -1)

    /** The system refused the lookup: the feature does not work on this OS version. */
    val denied: Boolean get() = p.getBoolean("denied", false)

    /** Date of the store catalog in use (0 = the one bundled with the app). */
    fun catalogTime(): Long = File(app.filesDir, CATALOG).lastModified()

    /**
     * The library also lists Meta's own pieces (home environments, keyboards, services):
     * owned by everyone, not something to show as a game waiting to be installed.
     */
    fun worthShowing(pkg: String) =
        !Meta.isPlatformPackage(pkg) || pkg.contains(".samples") || pkg == "com.oculus.tinycastles"

    /** Fetches covers that are still missing for the owned games. */
    fun requestCovers() {
        if (Store.fetchMeta) Meta.requestCovers(pkgs().filter { worthShowing(it) })
    }

    /** Install time of this build: a new build may bring a newer catalog, so it re-checks. */
    private fun build(): Long = try {
        app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
    } catch (e: Exception) {
        0L
    }

    /** When this process last tried: a refused or failed check must not be repeated on every redraw. */
    @Volatile
    private var lastAttempt = 0L

    fun scanIfStale() {
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RESCAN_MS) return
        if (now - time > RESCAN_MS || p.getLong("build", 0) != build()) scan()
    }

    fun scan() {
        if (!scanning.compareAndSet(false, true)) return
        lastAttempt = System.currentTimeMillis()
        pool.execute {
            try {
                doScan()
            } catch (e: Exception) {
                Log.w(TAG, "owned scan failed", e)
            } finally {
                busy = ""
                scanning.set(false)
                onUpdate?.invoke()
            }
        }
    }

    /** Downloads the whole store catalog again (large), then re-checks the library. */
    fun refreshCatalog() {
        if (!scanning.compareAndSet(false, true)) return
        pool.execute {
            try {
                doRefresh()
                doScan()
            } catch (e: Exception) {
                Log.w(TAG, "catalog refresh failed", e)
            } finally {
                busy = ""
                scanning.set(false)
                onUpdate?.invoke()
            }
        }
    }

    private fun openCatalog(): BufferedReader {
        val f = File(app.filesDir, CATALOG)
        val input = if (f.exists()) f.inputStream() else app.assets.open(CATALOG)
        return BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16)
    }

    private fun doScan() {
        val started = System.currentTimeMillis()
        busyCount = 0
        busy = "scan"
        val client = try {
            app.contentResolver.acquireUnstableContentProviderClient(BASE)
        } catch (e: SecurityException) {
            null
        }
        if (client == null) {
            Log.w(TAG, "owned scan: library provider unavailable")
            p.edit().putBoolean("denied", true).apply()
            return
        }
        val found = HashSet<String>()
        var asked = 0
        try {
            var total = -1
            client.query(BASE, null, null, null, null)?.use { total = it.count }
            // the hand-made list covers games the public catalog knows only by title
            val extra = BufferedReader(InputStreamReader(app.assets.open(EXTRA), Charsets.UTF_8))
            (openCatalog().use { it.readLines() } + extra.use { it.readLines() }).let { lines ->
                for (line in lines) {
                    // package, store id, name, genres separated by '|'
                    val f = line.split('\t')
                    if (f.size < 3) continue
                    asked++
                    if (asked % 1000 == 0) busyCount = asked
                    val owned = client.query(Uri.withAppendedPath(BASE, f[0]), null, null, null, null)?.use { c ->
                        if (!c.moveToFirst()) return@use false
                        // old Gear VR / Go purchases cannot run on this headset
                        val i = c.getColumnIndex("platform")
                        val platform = if (i >= 0) c.getString(i) ?: "" else ""
                        platform.isEmpty() || platform == "ANDROID_6DOF"
                    } ?: false
                    if (owned) {
                        found.add(f[0])
                        val genres = if (f.size > 3 && f[3].isNotEmpty()) f[3].split('|') else emptyList()
                        Meta.put(f[0], f[2], genres, f[1])
                    }
                }
            }
            val before = p.getStringSet("pkgs", null)
            val fresh = HashSet(p.getStringSet("fresh", emptySet()) ?: emptySet())
            fresh.retainAll(found)
            if (before != null) {
                val added = found.filter { it !in before }
                val grew = total - p.getInt("total", -1)
                // The library itself must have grown by as much. Otherwise these are old
                // purchases that a newer catalog has only now put a name to.
                if (added.isNotEmpty() && p.getInt("total", -1) >= 0 && added.size <= grew) {
                    fresh.addAll(added)
                    Log.i(TAG, "owned scan: new in the library: $added")
                }
            }
            p.edit()
                .putStringSet("pkgs", found)
                .putStringSet("fresh", fresh)
                .putLong("time", System.currentTimeMillis())
                .putInt("total", total)
                .putLong("build", build())
                .putBoolean("denied", false)
                .apply()
            cache = found
            requestCovers()
            Log.i(
                TAG,
                "owned scan: ${found.size} of $total library entries recognised, " +
                    "$asked packages asked in ${System.currentTimeMillis() - started} ms"
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "owned scan: access denied", e)
            p.edit().putBoolean("denied", true).apply()
        } finally {
            client.close()
        }
    }

    // ---- catalog refresh ---------------------------------------------------

    private fun str(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        else -> {
            r.skipValue()
            ""
        }
    }

    private fun strings(r: JsonReader): List<String> {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return emptyList()
        }
        val out = ArrayList<String>()
        r.beginArray()
        while (r.hasNext()) out.add(str(r))
        r.endArray()
        return out
    }

    private fun clean(s: String) = s.replace(Regex("[\\t\\r\\n|]+"), " ").trim()

    /** Streams the catalog (over 100 MB of JSON) and keeps four short fields per app. */
    private fun doRefresh() {
        busyCount = 0
        busy = "catalog"
        val c = URL(ALL_APPS).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "QuestShelf-launcher")
        // package -> (preference, line)
        val best = HashMap<String, Pair<String, String>>()
        try {
            if (c.responseCode != 200) {
                Log.w(TAG, "catalog refresh: http ${c.responseCode}")
                return
            }
            val r = JsonReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
            var seen = 0
            r.beginArray()
            while (r.hasNext()) {
                var type = ""
                var binary = ""
                var pkg = ""
                var id = ""
                var display = ""
                var name = ""
                var updated = ""
                var genres: List<String> = emptyList()
                var hmds: List<String> = emptyList()
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "__OculusDBType" -> type = str(r)
                        "binaryType" -> binary = str(r)
                        "packageName" -> pkg = clean(str(r))
                        "id" -> id = clean(str(r))
                        "displayName" -> display = clean(str(r))
                        "appName" -> name = clean(str(r))
                        "__lastUpdated" -> updated = str(r)
                        "genre_names" -> genres = strings(r)
                        "supported_hmd_platforms" -> hmds = strings(r)
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                seen++
                if (seen % 1000 == 0) busyCount = seen
                val title = display.ifEmpty { name }
                // binaryType 2 = PC builds
                if (type != "Application" || binary == "2") continue
                if (pkg.isEmpty() || id.isEmpty() || title.isEmpty() || pkg.contains(' ')) continue
                // the same package can be listed twice: prefer the Quest 3 entry, then the newer one
                val pref = (if ("EUREKA" in hmds) "2" else "0") + updated
                val old = best[pkg]
                if (old == null || pref > old.first) {
                    val g = genres.map { clean(it) }.filter { it.isNotEmpty() }.joinToString("|")
                    best[pkg] = Pair(pref, "$pkg\t$id\t$title\t$g")
                }
            }
            r.endArray()
        } finally {
            c.disconnect()
        }
        if (best.size < 5000) {
            Log.w(TAG, "catalog refresh: only ${best.size} apps, keeping the old catalog")
            return
        }
        val tmp = File(app.filesDir, "$CATALOG.tmp")
        tmp.bufferedWriter().use { w ->
            for (k in best.keys.sorted()) {
                w.write(best.getValue(k).second)
                w.write("\n")
            }
        }
        tmp.renameTo(File(app.filesDir, CATALOG))
        Log.i(TAG, "catalog refresh: ${best.size} apps")
    }
}
