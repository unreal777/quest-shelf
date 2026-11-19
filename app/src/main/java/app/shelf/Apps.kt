package app.shelf

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.Log

class AppEntry(
    val pkg: String,
    val sysLabel: String,
    /** Store name, or "" when unknown. */
    val storeName: String,
    val genres: List<String>,
    val icon: Drawable,
    /** True when [icon] is real cover art rather than the icon baked into the APK. */
    val hasCover: Boolean,
    val isSystem: Boolean,
    /** Installed by hand (adb, SideQuest…), not by the Meta store. */
    val isSideloaded: Boolean,
    val installed: Long,
    val updated: Long,
    /** Null for an app that is owned but not installed. */
    val launch: Intent?,
    val lastUsed: Long,
    val playTime: Long,
    /** Bought in the store, not on the headset right now. */
    val notInstalled: Boolean = false,
    /** Store page id, or "" when unknown. */
    val storeId: String = "",
    /** When the launcher noticed the app had been uninstalled, 0 = unknown. */
    val removed: Long = 0,
    /** Turned up in the library lately and has not been on the headset since. */
    val fresh: Boolean = false,
) {
    val demo: Boolean get() = Meta.isDemo(storeName.ifEmpty { sysLabel })
}

object Apps {
    /** Immersive apps on Quest declare this category instead of (or next to) LAUNCHER. */
    private const val VR_CATEGORY = "com.oculus.intent.category.VR"
    private const val STORE_INSTALLER = "com.oculus.ocms"

    /** Tile plate for an app that has neither a cover nor an APK to take an icon from. */
    private val blank = ColorDrawable(0xFF444446.toInt())

    /** package -> (version key, drawable) */
    private val iconCache = HashMap<String, Pair<String, Drawable>>()

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Start of the period the system has play statistics for, 0 = unknown. Anything played
     * before that (or on another headset) is simply not in the numbers.
     */
    @Volatile
    var statsSince = 0L
        private set

    private fun usage(c: Context): Map<String, UsageStats> {
        if (!hasUsageAccess(c)) return emptyMap()
        return try {
            val usm = c.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
            val all = usm.queryAndAggregateUsageStats(0L, System.currentTimeMillis())
            statsSince = all.values.filter { it.firstTimeStamp > 0 }.minOfOrNull { it.firstTimeStamp } ?: 0L
            all
        } catch (e: Exception) {
            Log.w("Shelf", "usage stats unavailable", e)
            emptyMap()
        }
    }

    private fun installer(pm: PackageManager, pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(pkg)
        }
    } catch (e: Exception) {
        null
    }

    private fun cover(pkg: String): Drawable? {
        val f = Meta.coverFile(pkg)
        if (!f.exists()) return null
        return try {
            // covers are 720px; a tile needs far less, and RGB_565 halves the memory again
            val o = BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(f.path, o)?.let { BitmapDrawable(null, it) }
        } catch (e: Throwable) {
            null
        }
    }

    /** Heavy: call from a background thread. */
    @Synchronized
    fun load(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val usage = usage(c)
        val own = Store.launched()
        val out = ArrayList<AppEntry>()
        val fromStore = HashMap<String, String>()
        val present = HashSet<String>()

        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(0)
        for (pi in packages) {
            val pkg = pi.packageName
            present.add(pkg)
            if (pkg == c.packageName) continue
            val ai = pi.applicationInfo ?: continue
            if (!ai.enabled) continue

            var launch = pm.getLaunchIntentForPackage(pkg)
            if (launch == null) {
                val vr = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(VR_CATEGORY).setPackage(pkg), 0
                ).firstOrNull()
                if (vr != null) {
                    launch = Intent(Intent.ACTION_MAIN)
                        .addCategory(VR_CATEGORY)
                        .setClassName(pkg, vr.activityInfo.name)
                }
            }
            if (launch == null) continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val meta = Meta.get(pkg)
            val sysLabel = ai.loadLabel(pm).toString()
            val preinstalled = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isStore = !preinstalled && installer(pm, pkg) == STORE_INSTALLER
            if (isStore) fromStore[pkg] = sysLabel
            // Meta's helpers delivered through the store (help centre, home environments…)
            // are not in the catalog; file them with the system apps
            val isSystem = preinstalled ||
                (isStore && Meta.isPlatformPackage(pkg) && meta != null && !meta.found)

            val coverFile = Meta.coverFile(pkg)
            val key = if (coverFile.exists()) "c${coverFile.lastModified()}" else "a${pi.lastUpdateTime}"
            val cached = iconCache[pkg]
            val icon = if (cached != null && cached.first == key) {
                cached.second
            } else {
                val d = cover(pkg) ?: try {
                    ai.loadIcon(pm)
                } catch (e: Exception) {
                    pm.defaultActivityIcon
                }
                iconCache[pkg] = Pair(key, d)
                d
            }

            val u = usage[pkg]
            out.add(
                AppEntry(
                    pkg = pkg,
                    sysLabel = sysLabel,
                    storeName = meta?.name ?: "",
                    genres = meta?.genres ?: emptyList(),
                    icon = icon,
                    hasCover = key.startsWith("c"),
                    isSystem = isSystem,
                    isSideloaded = !isSystem && !isStore,
                    installed = pi.firstInstallTime,
                    updated = pi.lastUpdateTime,
                    launch = launch,
                    lastUsed = maxOf(own[pkg] ?: 0L, u?.lastTimeUsed ?: 0L),
                    playTime = u?.totalTimeInForeground ?: 0L,
                    storeId = meta?.storeId ?: "",
                )
            )
        }

        // remember play time while the app is still here; note when it disappears
        val now = System.currentTimeMillis()
        val hist = Store.history()
        var histChanged = false
        for (a in out) {
            if (a.isSystem) continue
            val h = hist[a.pkg]
            val play = maxOf(h?.play ?: 0L, a.playTime)
            val used = maxOf(h?.used ?: 0L, a.lastUsed)
            if (h == null || h.play != play || h.used != used || h.removed != 0L) {
                hist[a.pkg] = Store.Hist(play, used, 0L)
                histChanged = true
            }
        }
        for ((pkg, h) in hist.entries.toList()) {
            if (h.removed == 0L && pkg !in present) {
                hist[pkg] = Store.Hist(h.play, h.used, now)
                histChanged = true
            }
        }
        if (histChanged) Store.setHistory(hist)

        // bought, but not on the headset
        val wantCover = ArrayList<String>()
        for (a in out) if (!a.hasCover && a.storeId.isNotEmpty() && !a.isSystem) wantCover.add(a.pkg)
        val fresh = Owned.fresh()
        for (pkg in Owned.pkgs()) {
            if (pkg in present || !Owned.worthShowing(pkg)) continue
            val meta = Meta.get(pkg) ?: continue
            if (!meta.found || meta.name.isBlank()) continue
            val coverFile = Meta.coverFile(pkg)
            val key = if (coverFile.exists()) "c${coverFile.lastModified()}" else "n"
            val cached = iconCache[pkg]
            val icon = if (cached != null && cached.first == key) {
                cached.second
            } else {
                val d = cover(pkg) ?: blank
                iconCache[pkg] = Pair(key, d)
                d
            }
            if (key == "n") wantCover.add(pkg)
            val h = hist[pkg]
            out.add(
                AppEntry(
                    pkg = pkg,
                    sysLabel = meta.name,
                    storeName = meta.name,
                    genres = meta.genres,
                    icon = icon,
                    hasCover = key != "n",
                    isSystem = false,
                    isSideloaded = false,
                    installed = 0L,
                    updated = 0L,
                    launch = null,
                    lastUsed = h?.used ?: 0L,
                    playTime = h?.play ?: 0L,
                    notInstalled = true,
                    storeId = meta.storeId,
                    removed = h?.removed ?: 0L,
                    // any history means it has been installed at some point
                    fresh = h == null && pkg in fresh,
                )
            )
        }

        if (Store.fetchMeta) {
            Meta.request(fromStore)
            Meta.requestCovers(wantCover)
        }
        Owned.scanIfStale()
        return out
    }
}
