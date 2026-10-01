package io.github.trickhook.shadowzap.hook.targets

import android.content.pm.ApplicationInfo
import io.github.trickhook.shadowzap.testing.TestEnvs
import org.junit.Assume
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostIndexTest {
    private val dir = TestEnvs.tempDir("host-index")

    private fun discovery(vararg packages: String, filter: Regex? = null) =
        TargetDiscovery<Class<*>>(packages.toList(), filter, null, null)

    @Test
    fun `indexes every root dex of the base and split APKs, sorted and distinct`() {
        val base = TestDex.zip(
            File(dir, "base.apk"),
            listOf(
                "AndroidManifest.xml" to ByteArray(16),
                "classes.dex" to TestDex.resource("fixture-039.dex"),
                "classes2.dex" to TestDex.dex(listOf("b.Two", "a.One")),
                "classes10.dex" to TestDex.dex(listOf("z.Ten")),
                // Not loaded by the runtime, so not indexed:
                "classes1.dex" to TestDex.dex(listOf("x.One")),
                "classes02.dex" to TestDex.dex(listOf("x.Zero")),
                "assets/classes.dex" to TestDex.dex(listOf("x.Asset")),
                "classes.dex.bak" to TestDex.dex(listOf("x.Backup")),
            ),
        )
        val split = TestDex.zip(
            File(dir, "split_feature.apk"),
            listOf("classes.dex" to TestDex.dex(listOf("s.Split", "a.One"))),
            stored = false,
        )
        val config = TestDex.zip(File(dir, "split_config.arm64_v8a.apk"), listOf("lib/arm64-v8a/libx.so" to ByteArray(8)))

        val index = HostIndex.build(listOf(base, split, config))
        val expected = (
            listOf(
                "com.android.tools.r8.annotations.LambdaMethod",
                "fixture.alpha.Kind",
                "fixture.alpha.Outer\$0",
                "fixture.alpha.Outer\$Callback",
                "fixture.alpha.Outer\$Inner",
                "fixture.alpha.Outer",
                "fixture.beta.Caf\u00E9",
                "fixture.beta.\u6570\u636E",
                "fixture.beta.\uD835\uDD18nicode",
            ) + listOf("a.One", "b.Two", "z.Ten", "s.Split")
            ).sorted()
        assertEquals(expected, index.classNames)
        assertEquals(3, index.apkCount)
        assertEquals(4, index.dexCount)
        assertTrue(index.complete, index.problems.toString())
        assertTrue(index.toString().startsWith("13 classes from 4 dex files in 3 APKs, built in "), index.toString())
    }

    @Test
    fun `unreadable APKs and dex files are recorded and the rest is still indexed`() {
        val base = TestDex.zip(
            File(dir, "base.apk"),
            listOf(
                "classes.dex" to TestDex.dex(listOf("a.Good")),
                "classes2.dex" to "not a dex at all, but long enough to hold a header?".repeat(4).toByteArray(),
                "classes3.dex" to TestDex.dex(listOf("b.AlsoGood")),
            ),
        )
        val notZip = File(dir, "split_broken.apk").apply { writeText("garbage") }
        val index = HostIndex.build(listOf(base, File(dir, "missing.apk"), notZip))
        assertEquals(listOf("a.Good", "b.AlsoGood"), index.classNames)
        assertEquals(1, index.apkCount)
        assertEquals(2, index.dexCount)
        assertFalse(index.complete)
        assertEquals(3, index.problems.size, index.problems.toString())
        assertTrue(index.problems[0].startsWith("base.apk!classes2.dex: "), index.problems[0])
        assertTrue(index.toString().endsWith("(incomplete: 3 problems)"), index.toString())
    }

    @Test
    fun `candidates are narrowed by package ranges and the name filter`() {
        val apk = TestDex.zip(
            File(dir, "base.apk"),
            listOf(
                "classes.dex" to TestDex.dex(
                    listOf(
                        "org.sample.host.theme.AshTheme",
                        "org.sample.host.theme.DarkTheme",
                        "org.sample.host.theme.utils.ColorUtilsKt",
                        "org.sample.host.themes.NotInPackage",
                        "org.sample.host.theme",
                        "org.sample.host.BuildConfig",
                        "com.facebook.react.common.assets.ReactFontManager",
                        "com.facebook.react.common.assets.ReactFontManager\$Companion",
                        "com.facebook.react.views.text.ReactFontManager",
                        "a1.b",
                    ),
                ),
            ),
        )
        val index = HostIndex.build(listOf(apk))
        assertEquals(
            listOf(
                "org.sample.host.theme.AshTheme",
                "org.sample.host.theme.DarkTheme",
                "org.sample.host.theme.utils.ColorUtilsKt",
            ),
            index.candidates(discovery("org.sample.host.theme")),
        )
        // Overlapping packages are scanned once.
        assertEquals(
            index.candidates(discovery("org.sample.host")),
            index.candidates(discovery("org.sample.host.theme", "org.sample.host", "org.sample.host.theme.utils")),
        )
        assertEquals(
            listOf("com.facebook.react.common.assets.ReactFontManager", "com.facebook.react.views.text.ReactFontManager"),
            index.candidates(discovery("com.facebook.react", filter = Regex("""\.ReactFontManager$"""))),
        )
        assertEquals(
            listOf("org.sample.host.theme.AshTheme", "org.sample.host.theme.DarkTheme"),
            index.candidates(discovery(filter = Regex("""^org\.sample\.host\.theme\.[A-Z]\w*Theme$"""))),
        )
        assertTrue(index.candidates(discovery("com.example")).isEmpty())
    }

    @Test
    fun `dex entry names follow the runtime's multidex naming`() {
        assertEquals(1, HostIndex.dexNumber("classes.dex"))
        assertEquals(2, HostIndex.dexNumber("classes2.dex"))
        assertEquals(42, HostIndex.dexNumber("classes42.dex"))
        for (name in listOf("classes1.dex", "classes0.dex", "classes02.dex", "Classes.dex", "a/classes.dex", "classes.dex2")) {
            assertNull(HostIndex.dexNumber(name), name)
        }
    }

    @Test
    fun `host APKs come from the application info`() {
        val info = ApplicationInfo()
        assertEquals(emptyList(), HostIndex.apksOf(info))
        info.sourceDir = "/data/app/x/base.apk"
        info.splitSourceDirs = arrayOf("/data/app/x/split_a.apk", "", "/data/app/x/base.apk", "/data/app/x/split_b.apk")
        assertEquals(
            listOf("/data/app/x/base.apk", "/data/app/x/split_a.apk", "/data/app/x/split_b.apk").map(::File),
            HostIndex.apksOf(info),
        )
    }

    /**
     * Measures the index of a real host build. Runs only when `SHADOWZAP_BENCH_HOST` names a directory with
     * `base.apk` (and optionally `classes.txt`, the expected class names, and loose `classes*.dex` files).
     */
    @Test
    fun `benchmark on a real host build`() {
        val root = System.getenv("SHADOWZAP_BENCH_HOST")?.let(::File)
        Assume.assumeTrue("SHADOWZAP_BENCH_HOST is not set", root != null && File(root, "base.apk").isFile)
        val apk = File(checkNotNull(root), "base.apk")

        val cold = HostIndex.build(listOf(apk))
        val warm = (1..10).map { HostIndex.build(listOf(apk)).buildMillis }.sorted()
        println("BENCH base.apk (${apk.length()} bytes): ${cold.size} classes, ${cold.dexCount} dex; cold ${cold.buildMillis} ms, warm median ${warm[warm.size / 2]} ms, min ${warm.first()} ms")
        assertTrue(cold.complete, cold.problems.toString())

        // The same dex files deflated: the worst case, every byte up to the descriptors is inflated.
        val deflated = File(dir, "deflated.apk")
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().filter { HostIndex.dexNumber(it.name) != null }
                .map { entry -> entry.name to zip.getInputStream(entry).use { it.readBytes() } }.toList()
            TestDex.zip(deflated, entries, stored = false)
        }
        val deflatedRuns = (1..5).map { HostIndex.build(listOf(deflated)).buildMillis }.sorted()
        println("BENCH deflated dex: median ${deflatedRuns[deflatedRuns.size / 2]} ms")
        assertEquals(cold.classNames, HostIndex.build(listOf(deflated)).classNames)

        val looseDex = root.listFiles { file -> HostIndex.dexNumber(file.name) != null }.orEmpty()
        if (looseDex.isNotEmpty()) {
            val counter = ReadCounter()
            val started = System.nanoTime()
            val names = looseDex.flatMap { file -> DexClassNames.read(file.length()) { counter.wrap(file.inputStream()) } }
            val millis = (System.nanoTime() - started) / 1_000_000
            println("BENCH loose dex: ${names.size} classes in $millis ms, read ${counter.bytesRead} of ${looseDex.sumOf { it.length() }} bytes")
        }

        val listing = File(root, "classes.txt")
        if (listing.isFile) {
            val expected = listing.readLines().map { it.removePrefix("\uFEFF").trim() }.filter { it.isNotEmpty() }.sorted()
            assertEquals(expected, cold.classNames)
        }
        assertTrue(cold.buildMillis < 1000, "index took ${cold.buildMillis} ms")
    }
}
