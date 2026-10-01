package io.github.trickhook.shadowzap.core

import java.io.File

/**
 * Every on-device path the module uses inside WhatsApp's data directory. Nothing else in the codebase may build
 * these paths by hand. Everything lives below `files/shadowzap/` (state that must survive) or `cache/shadowzap/`
 * (state the system or the user may clear), so the module never touches WhatsApp's own files.
 */
internal class Paths(val dataDir: File) {
    /** Persistent module state. */
    val filesDir: File = File(dataDir, "files/shadowzap")

    /** Disposable module state. */
    val cacheDir: File = File(dataDir, "cache/shadowzap")

    // ---- Hook targets ----

    /** Per-build caches of resolved hook targets (`hook/targets/HostTargets`). */
    val hookTargetsCacheDir: File = cacheDir

    /**
     * Resolved hook targets of one host build; [buildKey] combines the host version code, the module version code and
     * a hash of the host and module APK stamps (path, size, last modification). Small JSON.
     */
    fun hookTargetsCacheFile(buildKey: String): File =
        File(hookTargetsCacheDir, "hook-targets-${requireSafeName(buildKey)}.json")

    /**
     * Debug builds only: spec ids (one per line, `*` = all) whose targets must be found by discovery, skipping the
     * cache and the exact candidates, to simulate a host update. Release builds never read it.
     */
    val forceDiscoveryFile: File = File(filesDir, "debug/force-discovery")

    // ---- Settings ----

    /** Feature switches written by the Shadowzap settings page: `{"version":1,"enabled":{"<id>":true|false}}`. */
    val switchesFile: File = File(filesDir, "switches.json")

    // ---- Media ----

    /** Staging area for media being exported (Status downloads) before it is published to shared storage. */
    val mediaStagingDir: File = File(cacheDir, "media")

    private fun requireSafeName(name: String): String {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) {
            "Unsafe path component: '$name'"
        }
        return name
    }
}
