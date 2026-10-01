package io.github.trickhook.shadowzap.hook.targets

import io.github.trickhook.shadowzap.api.host.LogLevel
import io.github.trickhook.shadowzap.hook.targets.FakeHost.REACT
import io.github.trickhook.shadowzap.hook.targets.FakeHost.THEME
import io.github.trickhook.shadowzap.hook.targets.fakehost.react.common.assets.ReactFontManager
import io.github.trickhook.shadowzap.hook.targets.fakehost.react.runtime.BundleLoaderDelegate
import io.github.trickhook.shadowzap.hook.targets.fakehost.react.runtime.ReactInstance
import io.github.trickhook.shadowzap.hook.targets.fakehost.theme.DarkTheme
import io.github.trickhook.shadowzap.hook.targets.fakehost.theme.HostTheme
import io.github.trickhook.shadowzap.hook.targets.fakehost.theme.OnyxTheme
import io.github.trickhook.shadowzap.hook.targets.fakehost.theme.ThemeManager
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DiscoveryTest {
    private val host = FakeHostFixture()

    private fun themeSpec(id: String = "theme.darker", multiple: Boolean = false) = Targets.classes(id) {
        exact("$THEME.DarkerTheme")
        discover {
            packages(THEME)
            where { cls -> HostTheme::class.java.isAssignableFrom(cls) && !Modifier.isAbstract(cls.modifiers) }
        }
        this.multiple = multiple
    }

    @Test
    fun `a renamed class is found by structure and the closest name wins`() {
        val result = host.targets().resolve(themeSpec())
        assertEquals(Resolution.DISCOVERED, result.resolution)
        assertSame(DarkTheme::class.java, result.value)
        assertEquals("$THEME.DarkTheme (closest of 4 matches)", result.detail)
        assertTrue(host.messages(LogLevel.INFO).any { it == "theme.darker: DISCOVERED ${result.detail}" })
        val ambiguity = host.messages(LogLevel.WARN).single { "discovered matches" in it }
        val expected = "Target 'theme.darker': 4 discovered matches for a single target; " +
            "using $THEME.DarkTheme (closest to $THEME.DarkerTheme); also matched: "
        assertTrue(ambiguity.startsWith(expected), ambiguity)
        assertTrue("$THEME.AshTheme" in ambiguity && "$THEME.OnyxTheme" in ambiguity, ambiguity)
        assertEquals(1, host.indexBuilds())
    }

    @Test
    fun `multiple keeps every match in name order`() {
        val result = host.targets().resolve(themeSpec(multiple = true))
        assertEquals(Resolution.DISCOVERED, result.resolution)
        assertEquals(
            listOf("AshTheme", "DarkTheme", "LightTheme", "OnyxTheme"),
            result.values.map { it.simpleName },
        )
        assertFalse("closest" in result.detail, result.detail)
    }

    @Test
    fun `only names passing the prefilter are loaded, and failing classes are skipped`() {
        host.targets().resolve(themeSpec())
        val requested = host.loader.requested.toSet()
        val themeClasses = FakeHost.CLASSES.filter { it.startsWith("$THEME.") }
        assertTrue(requested.containsAll(themeClasses), requested.toString())
        assertFalse(FakeHost.NEIGHBOUR in requested, requested.toString())
        assertTrue(requested.none { it.startsWith("$REACT.") }, requested.toString())
        // GhostTheme (absent), BrokenTheme (does not link) and ExplodingTheme (throwing loader) are skipped.
        val debug = host.messages(LogLevel.DEBUG)
        assertTrue(debug.any { "${FakeHost.BROKEN} does not load: java.lang.NoClassDefFoundError" in it }, debug.toString())
        assertTrue(debug.any { "${FakeHost.EXPLODING} does not load: java.lang.IllegalStateException" in it }, debug.toString())
    }

    @Test
    fun `a moved class is found through the name filter`() {
        val loader = RecordingLoader(DiscoveryTest::class.java.classLoader!!)
        val result = host.targets(loader = loader).resolve(
            Targets.classes("fonts.manager") {
                exact("$REACT.views.text.ReactFontManager")
                discover {
                    packages(REACT)
                    nameFilter(Regex("""\.ReactFontManager$"""))
                }
            },
        )
        assertEquals(Resolution.DISCOVERED, result.resolution)
        assertSame(ReactFontManager::class.java, result.value)
        assertEquals("$REACT.common.assets.ReactFontManager", result.detail)
        // The name filter ran before loading: nothing else from the react package was loaded.
        assertEquals(
            listOf("$REACT.views.text.ReactFontManager", "$REACT.common.assets.ReactFontManager"),
            loader.requested.filter { it.startsWith("$REACT.") },
        )
    }

    @Test
    fun `a method is discovered in a renamed class, with the member constraints applied`() {
        val result = host.targets().resolve(
            Targets.method("script.fromFile") {
                exactIn("$REACT.runtime.ReactInstance\$loadJSBundle\$1")
                name = "loadScriptFromFile"
                params(String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                returns(Void.TYPE)
                concreteOnly = true
                discover {
                    packages("$REACT.runtime")
                    // The interface itself passes too; concreteOnly drops its abstract declaration.
                    where { cls -> BundleLoaderDelegate::class.java.isAssignableFrom(cls) }
                }
            },
        )
        assertEquals(Resolution.DISCOVERED, result.resolution, result.detail)
        val method = assertNotNull(result.value)
        assertEquals(ReactInstance.BundleDelegate::class.java, method.declaringClass)
        assertEquals("$REACT.runtime.ReactInstance\$BundleDelegate#loadScriptFromFile(String, String, boolean)", result.detail)
        val instance = ReactInstance()
        method.invoke(instance.BundleDelegate(), "bundle.js", "url", false)
        assertEquals(listOf("bundle.js"), instance.loaded)
    }

    @Test
    fun `method, constructor and field discovery honour their member predicates`() {
        val targets = host.targets()
        val assets = targets.resolve(
            Targets.method("script.fromAssets") {
                exactIn("$REACT.runtime.Gone")
                anyParams()
                discover {
                    packages("$REACT.runtime")
                    where { cls -> !cls.isInterface }
                    methodWhere { m -> m.name.endsWith("Assets") }
                }
            },
        )
        assertEquals("$REACT.runtime.ReactInstance\$BundleDelegate#loadScriptFromAssets(String, boolean)", assets.detail)

        val constructor = targets.resolve(
            Targets.constructor("theme.onyx.new") {
                exactIn("$THEME.DarkerTheme")
                params(Boolean::class.javaPrimitiveType)
                discover {
                    packages(THEME)
                    constructorWhere { c -> c.declaringClass.simpleName.startsWith("Onyx") }
                }
            },
        )
        assertEquals(Resolution.DISCOVERED, constructor.resolution)
        assertEquals(false, (assertNotNull(constructor.value).newInstance(false) as OnyxTheme).amoled)

        val field = targets.resolve(
            Targets.field("theme.current") {
                exactIn("$THEME.OldThemeStore")
                type(HostTheme::class.java)
                discover {
                    packages(THEME)
                    fieldWhere { f -> Modifier.isStatic(f.modifiers) && f.name.startsWith("cur") }
                }
            },
        )
        assertEquals(Resolution.DISCOVERED, field.resolution)
        assertEquals(ThemeManager::class.java.getDeclaredField("current"), field.value)
    }

    @Test
    fun `throwing predicates count as no match and never escape`() {
        val result = host.targets().resolve(
            Targets.classes("theme.throwing") {
                exact("$THEME.DarkerTheme")
                discover {
                    packages(THEME)
                    where { throw IllegalStateException("predicate bug") }
                }
            },
        )
        assertEquals(Resolution.MISSING, result.resolution)
        assertEquals(
            "exact: $THEME.DarkerTheme (not found); discovery found nothing (9 candidate classes, 6 loaded, none matched)",
            result.detail,
        )
        assertTrue(host.messages(LogLevel.DEBUG).any { "where { } threw" in it && "predicate bug" in it })
    }

    @Test
    fun `a predicate may resolve another spec, and discovery reuses one index`() {
        val base = Targets.classes("theme.base") {
            exact("$THEME.HostThemeRenamed")
            discover {
                packages(THEME)
                where { cls -> Modifier.isAbstract(cls.modifiers) }
            }
        }
        val targets = host.targets()
        val derived = targets.resolve(
            Targets.classes("theme.first") {
                exact("$THEME.DarkerTheme")
                discover {
                    packages(THEME)
                    where { cls ->
                        val theme = targets.resolve(base).value
                        theme != null && theme != cls && theme.isAssignableFrom(cls)
                    }
                }
            },
        )
        assertEquals(HostTheme::class.java, targets.resolve(base).value)
        assertEquals(Resolution.DISCOVERED, derived.resolution)
        assertEquals("$THEME.DarkTheme (closest of 4 matches)", derived.detail)
        assertEquals(1, host.indexBuilds())
        assertEquals(listOf("theme.base", "theme.first"), targets.report().map { it.spec.id })
        assertEquals("targets: 0 exact, 0 cached, 2 discovered, 0 missing", targets.summary())
    }

    @Test
    fun `discovery does not run when an exact candidate matches`() {
        val result = host.targets().resolve(
            Targets.classes("theme.dark") {
                exact("$THEME.DarkTheme")
                discover { packages(THEME) }
            },
        )
        assertEquals(Resolution.EXACT, result.resolution)
        assertEquals(0, host.indexBuilds())
        assertTrue(host.cacheFiles().isEmpty())
    }

    @Test
    fun `without host APK paths discovery is unavailable`() {
        val env = host.env.also { it.appInfo.sourceDir = null }
        val result = host.targets(env = env).resolve(themeSpec())
        assertEquals(Resolution.MISSING, result.resolution)
        assertTrue(result.detail.endsWith("; discovery unavailable"), result.detail)
        assertTrue(host.messages(LogLevel.WARN).any { it == "Discovery unavailable: the host APK paths are unknown" })
    }

    @Test
    fun `an unreadable host APK makes discovery unavailable`() {
        host.apk.writeText("not a zip")
        val result = host.targets().resolve(themeSpec())
        assertTrue(result.detail.endsWith("; discovery unavailable"), result.detail)
        assertTrue(host.cacheFiles().none { it.name.endsWith(".json") })
    }

    @Test
    fun `a nameFilter-only discovery scans the whole index`() {
        val result = host.targets().resolve(
            Targets.classes("theme.byName") {
                exact("$THEME.DarkerTheme")
                discover { nameFilter(Regex("""\.Onyx\w+$""")) }
            },
        )
        assertSame(OnyxTheme::class.java, result.value)
    }
}
