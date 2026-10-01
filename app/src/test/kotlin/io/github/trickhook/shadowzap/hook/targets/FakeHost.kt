package io.github.trickhook.shadowzap.hook.targets

import io.github.trickhook.shadowzap.api.host.LogLevel
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.HostInfo
import io.github.trickhook.shadowzap.core.Paths
import io.github.trickhook.shadowzap.testing.RecordingLogger
import io.github.trickhook.shadowzap.testing.TestEnvs
import java.io.File
import java.util.Collections

/** Names of the stand-in host classes under `fakehost/` (JVM test classes playing an obfuscated host app). */
internal object FakeHost {
    const val ROOT = "io.github.trickhook.shadowzap.hook.targets.fakehost"
    const val THEME = "$ROOT.theme"
    const val REACT = "$ROOT.react"

    /** Listed in the fake APK's dex but not on the class path. */
    const val GHOST = "$THEME.GhostTheme"

    /** The fake host's class loader fails to link it (NoClassDefFoundError). */
    const val BROKEN = "$THEME.BrokenTheme"

    /** The fake host's class loader throws a RuntimeException for it. */
    const val EXPLODING = "$THEME.ExplodingTheme"

    /** Listed in the dex in a sibling package whose name starts like [THEME]; not a real class. */
    const val NEIGHBOUR = "${ROOT}.themex.NotInPackage"

    val CLASSES = listOf(
        "$THEME.AshTheme",
        "$THEME.DarkTheme",
        "$THEME.HostTheme",
        "$THEME.LightTheme",
        "$THEME.OnyxTheme",
        "$THEME.ThemeManager",
        GHOST,
        BROKEN,
        EXPLODING,
        NEIGHBOUR,
        "$REACT.common.assets.ReactFontManager",
        "$REACT.common.assets.ReactFontManager\$Companion",
        "$REACT.common.assets.ReactTypefaceUtils",
        "$REACT.runtime.BundleLoaderDelegate",
        "$REACT.runtime.ReactInstance",
        "$REACT.runtime.ReactInstance\$BundleDelegate",
        "$REACT.runtime.ScriptDecoy",
    )
}

/** Records every class requested through it and fails for the names in [failures]. */
internal class RecordingLoader(
    parent: ClassLoader,
    private val failures: Map<String, () -> Throwable> = mapOf(
        FakeHost.BROKEN to { NoClassDefFoundError("fake/Missing") },
        FakeHost.EXPLODING to { IllegalStateException("class loader exploded") },
    ),
) : ClassLoader(parent) {
    val requested: MutableList<String> = Collections.synchronizedList(ArrayList())

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        requested += name
        failures[name]?.let { throw it() }
        return super.loadClass(name, resolve)
    }
}

/**
 * A fake host: a data directory, a base APK whose dex lists [classes], an [Env] pointing at it and a recording
 * class loader over the test class path.
 */
internal class FakeHostFixture(
    classes: List<String> = FakeHost.CLASSES,
    extraEntries: List<Pair<String, ByteArray>> = emptyList(),
    val root: File = TestEnvs.tempDir("fake-host"),
) {
    val dataDir = File(root, "data")
    val paths = Paths(dataDir)
    val apk: File = TestDex.zip(File(root, "base.apk"), listOf("classes.dex" to TestDex.dex(classes)) + extraEntries)
    val env: Env = TestEnvs.env(dataDir).also { it.appInfo.sourceDir = apk.path }
    val log = RecordingLogger()
    val loader = RecordingLoader(FakeHostFixture::class.java.classLoader!!)

    fun targets(forceDiscoveryFile: File? = null, env: Env = this.env, loader: ClassLoader = this.loader) =
        HostTargets(loader, paths, env, HostInfo(), log, forceDiscoveryFile)

    /** The `hook-targets-*` files in the cache directory, sorted by name. */
    fun cacheFiles(): List<File> =
        paths.hookTargetsCacheDir.listFiles().orEmpty().filter { it.name.startsWith("hook-targets-") }.sortedBy { it.name }

    /** The single current cache file (fails when there is not exactly one `.json`). */
    fun cacheFile(): File = cacheFiles().single { it.name.endsWith(".json") }

    fun messages(level: LogLevel): List<String> =
        synchronized(log.entries) { log.entries.filter { it.first == level }.map { it.second } }

    fun indexBuilds(): Int = messages(LogLevel.INFO).count { it.startsWith("Host index: ") }
}
