package io.github.trickhook.shadowzap.hook.targets

import io.github.trickhook.shadowzap.api.host.LogLevel
import io.github.trickhook.shadowzap.core.EntryKind
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.HostInfo
import io.github.trickhook.shadowzap.hook.targets.FakeHost.REACT
import io.github.trickhook.shadowzap.hook.targets.FakeHost.THEME
import io.github.trickhook.shadowzap.hook.targets.fakehost.react.runtime.BundleLoaderDelegate
import io.github.trickhook.shadowzap.hook.targets.fakehost.theme.DarkTheme
import io.github.trickhook.shadowzap.hook.targets.fakehost.theme.HostTheme
import io.github.trickhook.shadowzap.testing.RecordingLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TargetCacheTest {
    private val host = FakeHostFixture()

    private val theme = Targets.classes("theme.darker") {
        exact("$THEME.DarkerTheme")
        discover {
            packages(THEME)
            where { cls -> HostTheme::class.java.isAssignableFrom(cls) && !Modifier.isAbstract(cls.modifiers) }
        }
    }

    private val script = Targets.method("script.fromFile") {
        exactIn("$REACT.runtime.ReactInstance\$loadJSBundle\$1")
        name = "loadScriptFromFile"
        params(String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
        concreteOnly = true
        discover {
            packages("$REACT.runtime")
            where { cls -> BundleLoaderDelegate::class.java.isAssignableFrom(cls) }
        }
    }

    private val nothing = Targets.classes("theme.none") {
        exact("$THEME.DarkerTheme")
        discover {
            packages(THEME)
            nameFilter(Regex("Nonexistent"))
        }
    }

    private fun json(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    @Test
    fun `discoveries are written per build and come back as CACHED`() {
        val first = host.targets()
        assertEquals(Resolution.DISCOVERED, first.resolve(theme).resolution)
        assertEquals(Resolution.DISCOVERED, first.resolve(script).resolution)

        val file = host.cacheFile()
        assertTrue(file.name.matches(Regex("""hook-targets-x-0-[0-9a-f]{12}\.json""")), file.name)
        val document = json(file)
        assertEquals(TargetCache.FORMAT, document["format"]?.jsonPrimitive?.content?.toInt())
        assertEquals(file.name.removePrefix("hook-targets-").removeSuffix(".json"), document["key"]?.jsonPrimitive?.content)
        val entries = document["entries"]!!.jsonObject
        assertEquals(listOf("script.fromFile", "theme.darker"), entries.keys.toList())
        val scriptValue = entries["script.fromFile"]!!.jsonObject["values"]!!.jsonArray.single().jsonObject
        assertEquals("$REACT.runtime.ReactInstance\$BundleDelegate", scriptValue["class"]?.jsonPrimitive?.content)
        assertEquals("loadScriptFromFile", scriptValue["name"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("java.lang.String", "java.lang.String", "boolean"),
            scriptValue["params"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("void", scriptValue["type"]?.jsonPrimitive?.content)

        val second = host.targets()
        val cachedTheme = second.resolve(theme)
        assertEquals(Resolution.CACHED, cachedTheme.resolution)
        assertSame(DarkTheme::class.java, cachedTheme.value)
        assertEquals("$THEME.DarkTheme", cachedTheme.detail)
        val cachedScript = second.resolve(script)
        assertEquals(Resolution.CACHED, cachedScript.resolution)
        assertEquals(first.resolve(script).value, cachedScript.value)
        assertEquals("targets: 0 exact, 2 cached, 0 discovered, 0 missing", second.summary())
        // The second start never indexed the host.
        assertEquals(1, host.indexBuilds())
    }

    @Test
    fun `a discovery that found nothing is remembered for the build`() {
        val first = host.targets().resolve(nothing)
        assertEquals(Resolution.MISSING, first.resolution)
        assertTrue(first.detail.endsWith("discovery found nothing (no host class passes the packages and name filter)"))
        val entry = json(host.cacheFile())["entries"]!!.jsonObject["theme.none"]!!.jsonObject
        assertEquals(JsonArray(emptyList()), entry["values"])

        val second = host.targets().resolve(nothing)
        assertEquals(Resolution.MISSING, second.resolution)
        assertEquals(
            "exact: $THEME.DarkerTheme (not found); discovery found nothing (remembered for this build)",
            second.detail,
        )
        assertEquals(1, host.indexBuilds())
    }

    @Test
    fun `nothing found in an incomplete index is not remembered`() {
        val incomplete = FakeHostFixture(extraEntries = listOf("classes2.dex" to ByteArray(300)))
        val result = incomplete.targets().resolve(nothing)
        assertTrue(result.detail.endsWith("; the host index is incomplete"), result.detail)
        assertTrue(incomplete.cacheFiles().isEmpty())
        // What is found is still remembered.
        incomplete.targets().resolve(theme)
        assertEquals(listOf("theme.darker"), json(incomplete.cacheFile())["entries"]!!.jsonObject.keys.toList())
    }

    @Test
    fun `a corrupt cache file is quarantined and rebuilt`() {
        host.targets().resolve(theme)
        val file = host.cacheFile()
        file.writeText("{ this is not json")

        val result = host.targets().resolve(theme)
        assertEquals(Resolution.DISCOVERED, result.resolution)
        val files = host.cacheFiles().map { it.name }
        assertEquals(2, files.size, files.toString())
        assertTrue(files.any { it.startsWith("${file.name}.corrupt.") }, files.toString())
        assertEquals(listOf("theme.darker"), json(file)["entries"]!!.jsonObject.keys.toList())
        assertTrue(host.messages(LogLevel.WARN).any { it.startsWith("Hook target cache ${file.name} is unreadable") })

        // Valid JSON of the wrong shape is treated the same way.
        file.writeText("[1, 2, 3]")
        assertEquals(Resolution.DISCOVERED, host.targets().resolve(theme).resolution)
        assertEquals(Resolution.CACHED, host.targets().resolve(theme).resolution)
    }

    @Test
    fun `an unreadable entry is dropped alone`() {
        val first = host.targets()
        first.resolve(theme)
        first.resolve(script)
        val file = host.cacheFile()
        val text = file.readText()
        val broken = text.replaceFirst(""""kind": "METHOD"""", """"kind": ["METHOD"]""")
        assertNotEquals(text, broken)
        file.writeText(broken)

        val second = host.targets()
        assertEquals(Resolution.CACHED, second.resolve(theme).resolution)
        assertEquals(Resolution.DISCOVERED, second.resolve(script).resolution)
        assertTrue(host.messages(LogLevel.WARN).any { it.startsWith("Dropping unreadable hook target cache entry 'script.fromFile'") })
        assertFalse(host.cacheFiles().any { ".corrupt." in it.name })
    }

    @Test
    fun `a cached entry that no longer loads or matches is dropped and resolved again`() {
        host.targets().resolve(theme)
        val file = host.cacheFile()
        val stale = file.readText().replace("$THEME.DarkTheme", "$THEME.GhostTheme")
        file.writeText(stale)

        val result = host.targets().resolve(theme)
        assertEquals(Resolution.DISCOVERED, result.resolution)
        assertTrue(
            host.messages(LogLevel.INFO).any {
                it == "Target 'theme.darker': cached entry no longer applies ($THEME.GhostTheme does not load); resolving again"
            },
            host.log.entries.toString(),
        )
        assertTrue("$THEME.DarkTheme" in file.readText())

        // An entry naming a class that fails the spec's predicate now.
        file.writeText(file.readText().replace("$THEME.DarkTheme", "$THEME.ThemeManager"))
        assertEquals(Resolution.DISCOVERED, host.targets().resolve(theme).resolution)
        assertTrue(host.messages(LogLevel.INFO).any { "$THEME.ThemeManager no longer passes where { }" in it })

        // An entry whose member signature changed.
        host.targets().resolve(script)
        file.writeText(file.readText().replace("\"boolean\"", "\"int\""))
        assertEquals(Resolution.DISCOVERED, host.targets().resolve(script).resolution)
        assertTrue(host.messages(LogLevel.INFO).any { "script.fromFile': cached entry no longer applies" in it && "is gone" in it })
    }

    @Test
    fun `files of other builds, old quarantines and abandoned temp files are pruned`() {
        host.targets().resolve(theme)
        val current = host.cacheFile()
        val dir = current.parentFile
        val old = System.currentTimeMillis() - 60 * 60 * 1000
        val otherKey = File(dir, "hook-targets-347012-2000-aaaaaaaaaaaa.json").apply { writeText("{}") }
        val otherCorrupt = File(dir, "hook-targets-1-1-bbbbbbbbbbbb.json.corrupt.1700000000000").apply { writeText("x") }
        val olderQuarantine = File(dir, "${current.name}.corrupt.1700000000000").apply { writeText("x") }
        val newerQuarantine = File(dir, "${current.name}.corrupt.1800000000000").apply { writeText("x") }
        val abandoned = File(dir, ".${current.name}.1234abcd.tmp").apply { writeText("x"); setLastModified(old) }
        val writing = File(dir, ".${current.name}.5678abcd.tmp").apply { writeText("x") }
        val unrelated = File(dir, "something-else.json").apply { writeText("{}") }

        assertEquals(Resolution.CACHED, host.targets().resolve(theme).resolution)
        for (gone in listOf(otherKey, otherCorrupt, olderQuarantine, abandoned)) assertFalse(gone.exists(), gone.name)
        for (kept in listOf(current, newerQuarantine, writing, unrelated)) assertTrue(kept.exists(), kept.name)
    }

    @Test
    fun `the key changes with the host or module APK and the loader version`() {
        val first = host.targets()
        first.resolve(theme)
        val before = host.cacheFile()

        // The module APK was updated: a new key, discovery runs again, the old file is pruned.
        val module = File(host.root, "module.apk").apply { writeText("module v2") }
        val updated = Env(
            entry = EntryKind.MODERN,
            packageName = "org.sample.host",
            processName = "org.sample.host",
            appInfo = host.env.appInfo,
            hostClassLoader = host.env.hostClassLoader,
            modulePath = module.path,
            frameworkDescription = "test",
            loaderVersion = "0.0.1-test",
            loaderVersionCode = 7,
        )
        assertEquals(Resolution.DISCOVERED, host.targets(env = updated).resolve(theme).resolution)
        val after = host.cacheFile()
        assertNotEquals(before.name, after.name)
        assertTrue(after.name.startsWith("hook-targets-x-7-"), after.name)
        assertFalse(before.exists())

        // Host APK rewritten (an update): another key.
        host.apk.setLastModified(host.apk.lastModified() - 10_000)
        assertEquals(Resolution.DISCOVERED, host.targets(env = updated).resolve(theme).resolution)
        assertNotEquals(after.name, host.cacheFile().name)
    }

    @Test
    fun `build keys are stable, carry the version codes and hash the APK stamps`() {
        val apk = host.apk
        val stamp = BuildKey.stamp(listOf(apk), "", "1.0")
        assertEquals("host=${apk.path}|${apk.length()}|${apk.lastModified()}\nloader=1.0\n", stamp)
        val key = BuildKey.of(347012, 2000, stamp)
        assertTrue(key.value.matches(Regex("""347012-2000-[0-9a-f]{12}""")), key.value)
        assertEquals(key.value, BuildKey.of(347012, 2000, stamp).value)
        assertNotEquals(key.value, BuildKey.of(347012, 2000, stamp + "module=x\n").value)
        assertEquals("x-2000-", BuildKey.of(null, 2000, stamp).value.dropLast(12))
    }

    @Test
    fun `forced discovery ignores the cache, rewrites it, and the next normal start uses it`() {
        val forceFile = host.paths.forceDiscoveryFile.apply {
            checkNotNull(parentFile).mkdirs()
            writeText("theme.dark\n")
        }
        val exactFirst = Targets.classes("theme.dark") {
            exact("$THEME.DarkTheme")
            discover {
                packages(THEME)
                where { cls -> HostTheme::class.java.isAssignableFrom(cls) && cls.simpleName.startsWith("Onyx") }
            }
        }
        val forced = host.targets(forceDiscoveryFile = forceFile).resolve(exactFirst)
        assertEquals(Resolution.DISCOVERED, forced.resolution)
        // The detail and a warning tell that discovery disagrees with the (skipped) exact step.
        assertEquals("$THEME.OnyxTheme [exact: $THEME.DarkTheme]", forced.detail)
        assertTrue(
            host.messages(LogLevel.WARN).any {
                it == "Target 'theme.dark': forced discovery found $THEME.OnyxTheme, but exact gives $THEME.DarkTheme"
            },
        )
        val entry = json(host.cacheFile())["entries"]!!.jsonObject["theme.dark"]!!.jsonObject
        assertEquals("true", entry["forced"]?.jsonPrimitive?.content)

        // Still forced: the cache is not read.
        assertEquals(Resolution.DISCOVERED, host.targets(forceDiscoveryFile = forceFile).resolve(exactFirst).resolution)

        // Without the switch the remembered discovery wins over the exact candidate, as after a real update.
        forceFile.delete()
        val normal = host.targets(forceDiscoveryFile = forceFile).resolve(exactFirst)
        assertEquals(Resolution.CACHED, normal.resolution)
        assertEquals("$THEME.OnyxTheme", normal.detail)
    }

    @Test
    fun `forced discovery reports whether it agrees with the exact step`() {
        val forceFile = host.paths.forceDiscoveryFile.apply {
            checkNotNull(parentFile).mkdirs()
            writeText("*")
        }
        val targets = host.targets(forceDiscoveryFile = forceFile)
        val same = targets.resolve(
            Targets.classes("theme.same") {
                exact("$THEME.DarkTheme")
                discover {
                    packages(THEME)
                    where { cls -> cls.simpleName.startsWith("Dark") }
                }
            },
        )
        assertEquals("$THEME.DarkTheme [same as exact]", same.detail)
        assertEquals("$THEME.DarkTheme (closest of 4 matches) [exact: none]", targets.resolve(theme).detail)
        val exactOnly = targets.resolve(Targets.classes("theme.exactOnly") { exact("$THEME.LightTheme") })
        assertEquals("exact skipped (forced discovery), exact has $THEME.LightTheme; no discovery", exactOnly.detail)
        // The same set in another order (discovery lists by name) agrees, without a warning.
        val reordered = targets.resolve(
            Targets.classes("theme.reordered") {
                exact("$THEME.OnyxTheme", "$THEME.DarkTheme")
                multiple = true
                discover {
                    packages(THEME)
                    where { cls -> cls.simpleName == "DarkTheme" || cls.simpleName == "OnyxTheme" }
                }
            },
        )
        assertEquals("$THEME.DarkTheme, $THEME.OnyxTheme [same as exact]", reordered.detail)
        assertTrue(host.messages(LogLevel.WARN).none { "theme.reordered" in it })
        assertEquals("targets: 0 exact, 0 cached, 3 discovered, 1 missing", targets.summary())
    }

    @Test
    fun `a spec reusing an id with another kind never touches the cache`() {
        val targets = host.targets()
        targets.resolve(theme)
        val before = host.cacheFile().readText()
        val clash = targets.resolve(
            Targets.method("theme.darker") {
                exactIn("$THEME.Gone")
                anyParams()
                discover { packages(THEME) }
            },
        )
        assertEquals(Resolution.DISCOVERED, clash.resolution)
        assertEquals(before, host.cacheFile().readText())
    }

    @Test
    fun `the cache is disabled when no key can be computed, and resolution goes on`() {
        val log = RecordingLogger()
        val cache = TargetCache(
            dir = host.paths.hookTargetsCacheDir,
            fileOf = host.paths::hookTargetsCacheFile,
            key = { throw IllegalStateException("no key") },
            hostInfo = HostInfo(),
            loaderVersionCode = 1,
            log = log,
        )
        assertNull(cache.get("a"))
        cache.put("a", CachedTarget("CLASS"))
        assertNull(cache.file)
        assertTrue(log.entries.any { it.first == LogLevel.WARN && it.second.startsWith("Hook target cache disabled") })

        val unsafe = TargetCache(
            dir = host.paths.hookTargetsCacheDir,
            fileOf = host.paths::hookTargetsCacheFile,
            key = { BuildKey("../escape", null) },
            hostInfo = HostInfo(),
            loaderVersionCode = 1,
            log = log,
        )
        unsafe.put("a", CachedTarget("CLASS"))
        assertNull(unsafe.file)
    }

    @Test
    fun `the version code cross-check warns when the package info disagrees`() {
        val log = RecordingLogger()
        val info = HostInfo().apply { versionCode = 347013 }
        val cache = TargetCache(
            dir = host.paths.hookTargetsCacheDir,
            fileOf = host.paths::hookTargetsCacheFile,
            key = { BuildKey("347012-1-abc", 347012) },
            hostInfo = info,
            loaderVersionCode = 1,
            log = log,
        )
        cache.put("a", CachedTarget("CLASS", listOf(CachedValue("a.B"))))
        assertTrue(log.entries.any { it.second == "Host version code 347012 (build key) differs from 347013 (package info)" })
        assertEquals(CachedTarget("CLASS", listOf(CachedValue("a.B"))), cache.get("a"))
        assertTrue(File(host.paths.hookTargetsCacheDir, "hook-targets-347012-1-abc.json").isFile)
    }
}
