package io.github.trickhook.shadowzap.hook.targets

import io.github.trickhook.shadowzap.api.host.Logger
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.HostInfo
import io.github.trickhook.shadowzap.core.Paths
import java.io.File
import java.lang.ref.SoftReference
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Member

/**
 * Finds hook targets in the host and remembers the outcome, so hooks keep working when WhatsApp renames or
 * moves a class or member (its R8-obfuscated classes change names with every build). Kernel service, exposed as `Kernel.targets` (and `FeatureContext.targets`).
 *
 * Resolution of one [TargetSpec], first success wins:
 * 1. [Resolution.CACHED]: the per-build cache ([TargetCache], `Paths.hookTargetsCacheFile`) holds an earlier
 *    discovery of this spec, and its classes and members still load and still satisfy the spec (constraints and
 *    discovery predicates). A remembered "discovery found nothing" skips step 3 for this build.
 * 2. [Resolution.EXACT]: one of the spec's exact candidates exists and has a member matching the constraints.
 * 3. [Resolution.DISCOVERED]: the [HostIndex] (every class name in the host's dex files, built on first need)
 *    narrowed by the spec's packages and name filter, each survivor loaded without initialisation and tested with
 *    the spec's predicates. Several matches for a single-valued spec: the class whose name is closest to the exact
 *    candidates wins ([NameSimilarity]) and the ambiguity is logged. The outcome is written to the cache.
 * 4. [Resolution.MISSING]: nothing matched; logged as a warning with the spec id. The caller skips its hook.
 *
 * Offline and private: only the host's own APKs and class loader are consulted. Results are memoized per spec id
 * for the life of the process. [resolve] is thread-safe and never throws.
 *
 * In debug builds only, `Paths.forceDiscoveryFile` (one spec id per line, or a line `*` for every spec) makes the
 * listed specs skip steps 1 and 2, to simulate a host update on a device; what discovery finds is still written to
 * the cache, so the next start without the file resolves those specs as [Resolution.CACHED]. The detail of a forced
 * result also tells what the skipped exact step gives (`[same as exact]`, `[exact: ...]` with a warning when they
 * differ, or `exact has ...` for a forced MISSING). The file must be readable by the host.
 */
internal class HostTargets(
    /** The host's class loader; candidates are loaded through it without being initialised. */
    private val loader: ClassLoader,
    private val paths: Paths,
    /** Process facts: the host's APK paths (`appInfo.sourceDir`, `splitSourceDirs`) and the loader version code. */
    private val env: Env,
    /** Host version facts; filled by the whatsapp-info feature, so still empty while earlier features install. */
    private val hostInfo: HostInfo,
    private val log: Logger,
    /** The debug force-discovery switch, or `null` (release builds) to ignore it entirely. */
    private val forceDiscoveryFile: File? = null,
) {
    private val lock = Any()

    /** Memo and report, in resolution order. Guarded by [lock]. */
    private val results = LinkedHashMap<String, ResolvedTarget<*>>()

    /** Ids being resolved on the thread that holds [lock], to stop a predicate from resolving its own spec. */
    private val resolving = HashSet<String>()

    /** Read on the first [resolve]. Guarded by [lock]. */
    private var forced: ForcedDiscovery? = null

    /** Discoveries of this host build; loaded on first use. Guarded by [lock]. */
    private val cache = TargetCache(
        dir = paths.hookTargetsCacheDir,
        fileOf = paths::hookTargetsCacheFile,
        key = ::buildKey,
        hostInfo = hostInfo,
        loaderVersionCode = env.loaderVersionCode,
        log = log,
    )

    /** Built on the first discovery; held softly since it is rarely needed again. Guarded by [lock]. */
    private var index: SoftReference<HostIndex>? = null

    /** Set once building the index failed, so a process tries only once. Guarded by [lock]. */
    private var indexUnavailable = false

    /**
     * Resolves [spec], or returns the memoized result of the first spec resolved with the same id (a spec of a
     * different kind with a reused id is resolved on its own, without the cache, not memoized, and logged as an
     * error). Predicates may resolve other specs. Never throws: failures become [Resolution.MISSING].
     */
    fun <T : Any> resolve(spec: TargetSpec<T>): ResolvedTarget<T> {
        synchronized(lock) {
            val previous = results[spec.id]
            if (previous != null) {
                if (previous.spec.kind == spec.kind) {
                    // Same kind, same value type (Class, Method, Constructor or Field).
                    @Suppress("UNCHECKED_CAST")
                    return previous as ResolvedTarget<T>
                }
                log.e(
                    "Target id '${spec.id}' is used by a ${previous.spec.kind} spec and a ${spec.kind} spec; " +
                        "ids must be unique",
                )
                return resolveAndLog(spec, useCache = false)
            }
            if (!resolving.add(spec.id)) {
                log.e("Target '${spec.id}' is resolved again from its own predicates; treated as missing there")
                return ResolvedTarget(spec, emptyList(), Resolution.MISSING, "recursive resolution")
            }
            try {
                return resolveAndLog(spec, useCache = true).also { results[spec.id] = it }
            } finally {
                resolving.remove(spec.id)
            }
        }
    }

    /**
     * The first value of [spec] for a hook that cannot work without it; throws
     * [io.github.trickhook.shadowzap.api.hooks.HookTargetNotFoundException] naming the spec and what was tried.
     */
    fun <T : Any> require(spec: TargetSpec<T>): T = resolve(spec).require()

    /** Every memoized result, in resolution order. */
    fun report(): List<ResolvedTarget<*>> = synchronized(lock) { results.values.toList() }

    /** One line for the boot log, e.g. `targets: 12 exact, 3 cached, 2 discovered, 0 missing`. */
    fun summary(): String {
        val counts = report().groupingBy { it.resolution }.eachCount()
        fun count(resolution: Resolution) = counts[resolution] ?: 0
        return "targets: ${count(Resolution.EXACT)} exact, ${count(Resolution.CACHED)} cached, " +
            "${count(Resolution.DISCOVERED)} discovered, ${count(Resolution.MISSING)} missing"
    }

    private fun <T : Any> resolveAndLog(spec: TargetSpec<T>, useCache: Boolean): ResolvedTarget<T> {
        val result = try {
            resolveUncached(spec, useCache)
        } catch (t: Throwable) {
            val failed = ResolvedTarget(spec, emptyList(), Resolution.MISSING, "resolution failed: $t")
            log.w(failed.toString(), t)
            return failed
        }
        when (result.resolution) {
            Resolution.EXACT -> log.d(result.toString())
            Resolution.CACHED, Resolution.DISCOVERED -> log.i(result.toString())
            Resolution.MISSING -> log.w(result.toString())
        }
        return result
    }

    private fun <T : Any> resolveUncached(spec: TargetSpec<T>, useCache: Boolean): ResolvedTarget<T> {
        val forcedHere = forcedDiscovery().covers(spec.id)

        var rememberedMissing = false
        if (useCache && !forcedHere) {
            val cached = fromCache(spec)
            if (cached != null) {
                if (cached.isNotEmpty()) return found(spec, cached, Resolution.CACHED)
                rememberedMissing = true
            }
        }

        val tried = ArrayList<String>()
        if (!forcedHere) {
            val exact = exactMatches(spec, tried)
            if (exact.isNotEmpty()) return found(spec, exact, Resolution.EXACT)
        }

        val discovered = if (spec.discovery == null || rememberedMissing) null else discover(spec, forcedHere, useCache)
        // Under the debug switch, also tell what the skipped exact step gives, so a device run shows whether
        // discovery finds the same targets.
        val exactUnderForce = if (forcedHere) exactQuietly(spec) else emptyList()
        if (discovered != null && discovered.values.isNotEmpty()) {
            val comparison = if (forcedHere) compareWithExact(spec, discovered.values, exactUnderForce) else ""
            return found(spec, discovered.values, Resolution.DISCOVERED, discovered.note + comparison)
        }

        val detail = buildString {
            when {
                forcedHere -> {
                    append("exact skipped (forced discovery)")
                    if (exactUnderForce.isNotEmpty()) append(", exact has ").append(describe(spec, exactUnderForce))
                }
                tried.isEmpty() -> append("no exact candidates")
                else -> append("exact: ").append(tried.joinToString("; "))
            }
            append("; ")
            append(
                when {
                    spec.discovery == null -> "no discovery"
                    rememberedMissing -> "discovery found nothing (remembered for this build)"
                    discovered == null -> "discovery unavailable"
                    else -> "discovery found nothing${discovered.note}"
                },
            )
        }
        return ResolvedTarget(spec, emptyList(), Resolution.MISSING, detail)
    }

    /** Step 2: the exact candidates in order; all of them when [TargetSpec.multiple], else the first match. */
    private fun <T : Any> exactMatches(spec: TargetSpec<T>, tried: MutableList<String>): List<T> {
        val matches = ArrayList<T>()
        for (name in spec.exactClassNames) {
            val cls = loadClass(name)
            if (cls == null) {
                tried += "$name (not found)"
                continue
            }
            val members = try {
                spec.candidatesIn(cls)
            } catch (t: Throwable) {
                log.d("Target '${spec.id}': could not inspect $name", t)
                tried += "$name (${t.javaClass.simpleName})"
                continue
            }
            if (members.isEmpty()) {
                tried += "$name (no ${spec.constraints})"
                continue
            }
            if (!spec.multiple) {
                if (members.size > 1) {
                    log.w(
                        "Target '${spec.id}': ${members.size} members of $name match ${spec.constraints}; using " +
                            "${spec.describe(members.first())} (narrow the spec or set multiple = true)",
                    )
                }
                return listOf(members.first())
            }
            matches += members
        }
        return matches.distinct()
    }

    /** [exactMatches] for the forced-discovery comparison only; never throws. */
    private fun <T : Any> exactQuietly(spec: TargetSpec<T>): List<T> = try {
        exactMatches(spec, ArrayList())
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        emptyList()
    }

    /**
     * Detail suffix of a forced discovery: whether it agrees with the exact candidates (a warning when not). Order
     * does not count: discovery lists classes by name, the exact step in candidate order.
     */
    private fun <T : Any> compareWithExact(spec: TargetSpec<T>, discovered: List<T>, exact: List<T>): String = when {
        exact.isEmpty() -> " [exact: none]"
        exact.size == discovered.size && exact.toSet() == discovered.toSet() -> " [same as exact]"
        else -> {
            val names = describe(spec, exact)
            log.w("Target '${spec.id}': forced discovery found ${describe(spec, discovered)}, but exact gives $names")
            " [exact: $names]"
        }
    }

    /**
     * Step 1: the cached values of [spec] for this host build (empty: discovery is remembered to have found
     * nothing), or `null` when there is no usable entry. An entry counts only when every class loads again, the
     * member with the cached signature is still among [TargetSpec.candidatesIn], and the discovery predicates still
     * accept both; otherwise it is dropped and resolution goes on.
     */
    private fun <T : Any> fromCache(spec: TargetSpec<T>): List<T>? {
        val entry = cache.get(spec.id) ?: return null
        if (entry.kind != spec.kind.name) {
            log.d("Target '${spec.id}': cached entry is a ${entry.kind}, not a ${spec.kind}; ignored")
            return null
        }
        val stale = try {
            return cachedValues(spec, entry)
        } catch (e: StaleEntry) {
            e.message
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            t.toString()
        }
        log.i("Target '${spec.id}': cached entry no longer applies ($stale); resolving again")
        cache.remove(spec.id)
        return null
    }

    private class StaleEntry(reason: String) : Exception(reason, null, false, false)

    private fun <T : Any> cachedValues(spec: TargetSpec<T>, entry: CachedTarget): List<T> {
        val discovery = spec.discovery ?: throw StaleEntry("the spec has no discover { } any more")
        if (entry.values.isEmpty()) return emptyList()
        if (!spec.multiple && entry.values.size > 1) {
            throw StaleEntry("${entry.values.size} values for a single-valued spec")
        }
        val values = ArrayList<T>(entry.values.size)
        for (cached in entry.values) {
            if (!discovery.acceptsName(cached.className)) {
                throw StaleEntry("${cached.className} no longer passes the packages or name filter")
            }
            val cls = loadQuietly(cached.className) ?: throw StaleEntry("${cached.className} does not load")
            val value = spec.candidatesIn(cls).firstOrNull { cached.matches(it) }
                ?: throw StaleEntry("$cached is gone or no longer matches ${spec.constraints}")
            if (!passes { discovery.acceptsClass(cls) }) throw StaleEntry("${cls.name} no longer passes where { }")
            if (!passes { discovery.acceptsMember(value) }) {
                throw StaleEntry("${spec.describe(value)} no longer passes the member test")
            }
            values += value
        }
        return values.distinct()
    }

    /** What step 3 found: the values ([TargetSpec.multiple] respected) and a note for the detail. */
    private class Discovered<T : Any>(val values: List<T>, val note: String)

    /**
     * Step 3: the classes (or members) found by structure, with an empty list when the scan found nothing, or
     * `null` when discovery is unavailable (no index). Writes the outcome to the cache when [useCache]; "nothing"
     * only when the index is complete.
     */
    private fun <T : Any> discover(spec: TargetSpec<T>, forcedHere: Boolean, useCache: Boolean): Discovered<T>? {
        val discovery = spec.discovery ?: return null
        val index = hostIndex() ?: return null
        val started = System.nanoTime()
        val names = index.candidates(discovery)
        if (names.size > MANY_CANDIDATES) {
            log.w(
                "Target '${spec.id}': ${names.size} classes pass the packages and name filter; discovery loads " +
                    "each of them (narrow packages(...) or nameFilter(...))",
            )
        }
        val matches = ArrayList<T>()
        var loaded = 0
        var failures = 0
        for (name in names) {
            val cls = loadQuietly(name) { failure ->
                if (++failures <= MAX_LOGGED_FAILURES) log.d("Target '${spec.id}': $name does not load: $failure")
            } ?: continue
            loaded++
            if (!passes(spec, "where { }", name) { discovery.acceptsClass(cls) }) continue
            val members = try {
                spec.candidatesIn(cls)
            } catch (t: Throwable) {
                if (t is VirtualMachineError) throw t
                log.d("Target '${spec.id}': could not inspect $name: $t")
                continue
            }
            for (member in members) {
                if (passes(spec, "the member test", name) { discovery.acceptsMember(member) }) matches += member
            }
        }
        val distinct = matches.distinct()
        val millis = (System.nanoTime() - started) / 1_000_000
        log.d(
            "Target '${spec.id}': discovery checked ${names.size} names, loaded $loaded classes " +
                "($failures failed), ${distinct.size} matches, in $millis ms",
        )

        val values: List<T>
        val note: String
        when {
            distinct.isEmpty() -> {
                values = distinct
                note = when {
                    names.isEmpty() -> " (no host class passes the packages and name filter)"
                    else -> " (${names.size} candidate classes, $loaded loaded, none matched)"
                } + if (index.complete) "" else "; the host index is incomplete"
            }
            spec.multiple || distinct.size == 1 -> {
                values = distinct
                note = ""
            }
            else -> {
                values = listOf(closest(spec, distinct))
                note = " (closest of ${distinct.size} matches)"
            }
        }
        if (useCache && (values.isNotEmpty() || index.complete)) remember(spec, values, forcedHere)
        return Discovered(values, note)
    }

    /** One of several [matches] for a single-valued spec: the most similar to the exact candidates, logged. */
    private fun <T : Any> closest(spec: TargetSpec<T>, matches: List<T>): T {
        // maxByOrNull keeps the first of equal scores: ties go to the deterministic (index) order.
        val best = matches.maxByOrNull { NameSimilarity.score(classNameOf(it), spec.exactClassNames) } ?: matches[0]
        val basis = when {
            spec.exactClassNames.isEmpty() -> "first in name order"
            else -> "closest to ${spec.exactClassNames.joinToString(" / ")}"
        }
        log.w(
            "Target '${spec.id}': ${matches.size} discovered matches for a single target; using " +
                "${spec.describe(best)} ($basis); also matched: ${describe(spec, matches.filter { it !== best })}. " +
                "Narrow the spec or set multiple = true",
        )
        return best
    }

    private fun <T : Any> remember(spec: TargetSpec<T>, values: List<T>, forcedHere: Boolean) {
        try {
            cache.put(
                spec.id,
                CachedTarget(spec.kind.name, values.map { CachedValue.of(it) }, forcedHere, System.currentTimeMillis()),
            )
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            log.w("Target '${spec.id}': could not remember the discovery", t)
        }
    }

    /** The index of the host's class names, built on first need; `null` when it cannot be built. */
    private fun hostIndex(): HostIndex? {
        index?.get()?.let { return it }
        if (indexUnavailable) return null
        val apks = HostIndex.apksOf(env.appInfo)
        if (apks.isEmpty()) {
            indexUnavailable = true
            log.w("Discovery unavailable: the host APK paths are unknown")
            return null
        }
        val built = try {
            HostIndex.build(apks)
        } catch (t: Throwable) {
            if (t is VirtualMachineError && t !is OutOfMemoryError) throw t
            indexUnavailable = true
            log.w("Discovery unavailable: could not index the host classes", t)
            return null
        }
        if (built.dexCount == 0) {
            indexUnavailable = true
            log.w("Discovery unavailable: no dex file of the host could be read (${built.problems.joinToString("; ")})")
            return null
        }
        log.i("Host index: $built")
        if (!built.complete) log.w("Host index is incomplete: ${built.problems.joinToString("; ")}")
        index = SoftReference(built)
        return built
    }

    private fun buildKey(): BuildKey {
        val stamp = BuildKey.stamp(HostIndex.apksOf(env.appInfo), env.modulePath, env.loaderVersion)
        // WhatsApp has no BuildConfig to read a version code from at boot; the APK stamps alone identify the build.
        return BuildKey.of(null, env.loaderVersionCode, stamp)
    }

    /** Loads [name] through the host class loader without initialising it; `null` when it does not load. */
    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (e: LinkageError) {
        log.d("Class $name exists but does not link: $e")
        null
    }

    /** Like [loadClass], but any failure (a throwing class loader included) is reported to [onFailure]. */
    private fun loadQuietly(name: String, onFailure: (Throwable) -> Unit = {}): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        onFailure(t)
        null
    }

    /** A discovery predicate; throwing counts as "no". */
    private inline fun passes(test: () -> Boolean): Boolean = try {
        test()
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        false
    }

    /** Like [passes], logging the failure. */
    private inline fun passes(spec: TargetSpec<*>, what: String, name: String, test: () -> Boolean): Boolean = try {
        test()
    } catch (t: Throwable) {
        if (t is VirtualMachineError) throw t
        log.d("Target '${spec.id}': $what threw for $name: $t")
        false
    }

    private fun classNameOf(value: Any): String = when (value) {
        is Class<*> -> value.name
        is Member -> value.declaringClass.name
        else -> value.toString()
    }

    private fun <T : Any> found(
        spec: TargetSpec<T>,
        values: List<T>,
        resolution: Resolution,
        note: String = "",
    ): ResolvedTarget<T> {
        for (value in values) {
            if (value is AccessibleObject) {
                try {
                    value.isAccessible = true
                } catch (_: Throwable) {
                    // Hooking works without it; only reflective calls on the member would fail.
                }
            }
        }
        return ResolvedTarget(spec, values, resolution, describe(spec, values) + note)
    }

    /** The names of [values], at most [MAX_LISTED] of them. */
    private fun <T : Any> describe(spec: TargetSpec<T>, values: List<T>): String {
        val listed = values.take(MAX_LISTED).joinToString(", ") { spec.describe(it) }
        return if (values.size > MAX_LISTED) "$listed (+${values.size - MAX_LISTED} more)" else listed
    }

    private fun forcedDiscovery(): ForcedDiscovery =
        forced ?: ForcedDiscovery.read(forceDiscoveryFile, log).also { forced = it }

    private companion object {
        const val MAX_LISTED = 6

        /**
         * Above this many candidate names a discovery is logged as too broad (it still runs). Loading about 5000 classes
         * takes about 100 ms on a Galaxy M52, once per build, so only scans of a large part of the whole app are worth
         * a warning.
         */
        const val MANY_CANDIDATES = 8000

        /** Class load failures logged per discovery; the rest are only counted. */
        const val MAX_LOGGED_FAILURES = 3
    }
}

/**
 * The debug force-discovery switch: spec ids whose cache and exact steps are skipped. One id per line, surrounding
 * whitespace and blank lines ignored; a line `*` covers every spec.
 */
internal class ForcedDiscovery private constructor(val all: Boolean, val ids: Set<String>) {
    fun covers(id: String): Boolean = all || id in ids

    val isEmpty: Boolean get() = !all && ids.isEmpty()

    companion object {
        val NONE = ForcedDiscovery(false, emptySet())

        private const val MAX_BYTES = 64L * 1024

        private const val BOM = "\uFEFF"

        fun parse(text: String): ForcedDiscovery {
            val lines = text.removePrefix(BOM).lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            return ForcedDiscovery("*" in lines, lines.filterTo(LinkedHashSet()) { it != "*" })
        }

        /** Reads [file] (`null` in release builds); a missing, oversized or unreadable file forces nothing. */
        fun read(file: File?, log: Logger): ForcedDiscovery {
            if (file == null) return NONE
            return try {
                if (!file.isFile) return NONE
                if (file.length() > MAX_BYTES) {
                    log.w("${file.path} is larger than $MAX_BYTES bytes; no discovery is forced")
                    return NONE
                }
                parse(file.readText()).also { forced ->
                    if (!forced.isEmpty) {
                        val which = if (forced.all) "every target" else forced.ids.joinToString()
                        log.w("Debug: ${file.path} forces discovery for $which")
                    }
                }
            } catch (t: Throwable) {
                log.w("Could not read ${file.path}; no discovery is forced", t)
                NONE
            }
        }
    }
}
