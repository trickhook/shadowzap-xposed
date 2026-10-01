package io.github.trickhook.shadowzap.hook.targets

import android.content.pm.ApplicationInfo
import java.io.File
import java.util.zip.ZipFile

/**
 * Every class name the host defines, read straight from the dex files of its APKs (base and splits) without loading
 * anything. Discovery narrows these names with each spec's packages and name filter before a single class is
 * loaded. Built lazily by [HostTargets], only when a discovery actually runs.
 */
internal class HostIndex private constructor(
    /** Binary class names (`a.b.Outer$Inner`), sorted and distinct. */
    val classNames: List<String>,
    /** APKs that were read (fully or partly). */
    val apkCount: Int,
    /** Dex files that were read. */
    val dexCount: Int,
    /** What could not be read; empty when the index is complete. */
    val problems: List<String>,
    val buildMillis: Long,
) {
    /**
     * `false` when an APK or dex file could not be read: a class may exist that is not listed. Discovery that finds
     * nothing in an incomplete index is not remembered.
     */
    val complete: Boolean get() = problems.isEmpty()

    val size: Int get() = classNames.size

    /**
     * The names that pass [discovery]'s name prefilter ([TargetDiscovery.acceptsName]), in index order. With
     * packages, only the sorted ranges of those packages are scanned.
     */
    fun candidates(discovery: TargetDiscovery<*>): List<String> {
        val out = ArrayList<String>()
        for (range in ranges(discovery.packages)) {
            for (i in range) {
                val name = classNames[i]
                if (discovery.acceptsName(name)) out += name
            }
        }
        return out
    }

    /** Index ranges holding the classes of [packages] and their subpackages, merged and ascending. */
    private fun ranges(packages: List<String>): List<IntRange> {
        if (packages.isEmpty()) return listOf(classNames.indices)
        // Every name starting with "pkg." sorts in ["pkg.", "pkg/"): '/' follows '.' and never occurs in names.
        val ranges = packages.map { pkg -> lowerBound("$pkg.") until lowerBound("$pkg/") }
            .filter { !it.isEmpty() }
            .sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    /** First index whose name is not less than [key]. */
    private fun lowerBound(key: String): Int {
        var low = 0
        var high = classNames.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (classNames[mid] < key) low = mid + 1 else high = mid
        }
        return low
    }

    override fun toString(): String =
        "$size classes from ${count(dexCount, "dex file")} in ${count(apkCount, "APK")}, built in $buildMillis ms" +
            if (complete) "" else " (incomplete: ${count(problems.size, "problem")})"

    private fun count(n: Int, what: String): String = if (n == 1) "1 $what" else "$n ${what}s"

    companion object {
        /** Upper bound on the names kept, far above any real app (WhatsApp 2.26.39 defines about 80000 classes). */
        const val MAX_CLASS_NAMES = 1_000_000

        /** Dex entries larger than this are not read (the largest real dex files are around 12 MB). */
        const val MAX_DEX_BYTES = 256L * 1024 * 1024

        private val DEX_ENTRY = Regex("""classes([2-9]|[1-9][0-9]+)?\.dex""")

        /** The host's APKs: the base APK, then the split APKs, without duplicates. */
        fun apksOf(appInfo: ApplicationInfo): List<File> {
            val paths = LinkedHashSet<String>()
            appInfo.sourceDir?.takeIf { it.isNotEmpty() }?.let(paths::add)
            appInfo.splitSourceDirs?.forEach { path -> if (!path.isNullOrEmpty()) paths += path }
            return paths.map(::File)
        }

        /**
         * Reads every `classes.dex`, `classes2.dex`, ... at the root of each of [apks]. An APK or dex file that
         * cannot be read is recorded in [problems] and skipped; the rest of the index is still built.
         */
        fun build(apks: List<File>): HostIndex {
            val started = System.nanoTime()
            val names = HashSet<String>()
            val problems = ArrayList<String>()
            var apkCount = 0
            var dexCount = 0
            for (apk in apks) {
                try {
                    ZipFile(apk).use { zip ->
                        apkCount++
                        val entries = zip.entries().asSequence()
                            .mapNotNull { entry -> dexNumber(entry.name)?.let { it to entry } }
                            .sortedBy { it.first }
                            .map { it.second }
                            .toList()
                        for (entry in entries) {
                            val where = "${apk.name}!${entry.name}"
                            if (entry.size > MAX_DEX_BYTES) {
                                problems += "$where: ${entry.size} bytes, not read"
                                continue
                            }
                            try {
                                val found = DexClassNames.read(entry.size) { zip.getInputStream(entry) }
                                dexCount++
                                for (name in found) {
                                    if (names.size >= MAX_CLASS_NAMES && name !in names) {
                                        problems += "$where: more than $MAX_CLASS_NAMES classes, the rest skipped"
                                        break
                                    }
                                    names += name
                                }
                            } catch (e: Exception) {
                                problems += "$where: $e"
                            }
                        }
                    }
                } catch (e: Exception) {
                    problems += "${apk.path}: $e"
                }
            }
            val sorted = names.toTypedArray().also { it.sort() }.asList()
            val millis = (System.nanoTime() - started) / 1_000_000
            return HostIndex(sorted, apkCount, dexCount, problems, millis)
        }

        /** 1 for `classes.dex`, N for `classesN.dex` (N >= 2, as the runtime names them), `null` otherwise. */
        internal fun dexNumber(entryName: String): Int? {
            val match = DEX_ENTRY.matchEntire(entryName) ?: return null
            return match.groupValues[1].ifEmpty { "1" }.toIntOrNull()
        }
    }
}
