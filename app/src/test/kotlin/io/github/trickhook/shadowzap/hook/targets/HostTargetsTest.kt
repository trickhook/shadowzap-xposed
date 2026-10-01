package io.github.trickhook.shadowzap.hook.targets

import io.github.trickhook.shadowzap.api.hooks.HookTargetNotFoundException
import io.github.trickhook.shadowzap.api.host.LogLevel
import io.github.trickhook.shadowzap.core.Bootstrap
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Feature
import io.github.trickhook.shadowzap.core.FeatureContext
import io.github.trickhook.shadowzap.core.HostInfo
import io.github.trickhook.shadowzap.core.LogEntry
import io.github.trickhook.shadowzap.core.LogSink
import io.github.trickhook.shadowzap.core.Paths
import io.github.trickhook.shadowzap.testing.FakeHookBackend
import io.github.trickhook.shadowzap.testing.RecordingLogger
import io.github.trickhook.shadowzap.testing.TestEnvs
import java.io.File
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Classes the tests resolve by name; their binary names contain `$` like the host's nested classes. */
@Suppress("unused")
internal object Fixtures {
    const val PREFIX = "io.github.trickhook.shadowzap.hook.targets.Fixtures\$"
    const val SAMPLE = PREFIX + "Sample"
    const val OTHER = PREFIX + "Other"
    const val BASE = PREFIX + "Base"

    abstract class Base {
        abstract fun run(value: String): Int
    }

    open class Sample : Base() {
        override fun run(value: String): Int = 1

        fun run(value: String, flag: Boolean): Int = if (flag) 2 else 3

        fun loadScriptFromFile(fileName: String, sourceUrl: String, sync: Boolean) = Unit

        @JvmField
        var count: Int = 0

        @JvmField
        val label: String = "sample"
    }

    class Other(val size: Int) {
        constructor() : this(0)
        constructor(text: String) : this(text.length)
    }
}

class HostTargetsTest {
    private val dataDir = TestEnvs.tempDir()
    private val paths = Paths(dataDir)
    private val log = RecordingLogger()

    private fun targets(forceDiscoveryFile: File? = null) = HostTargets(
        loader = HostTargetsTest::class.java.classLoader!!,
        paths = paths,
        env = TestEnvs.env(dataDir),
        hostInfo = HostInfo(),
        log = log,
        forceDiscoveryFile = forceDiscoveryFile,
    )

    private fun messages(level: LogLevel): List<String> = log.entries.filter { it.first == level }.map { it.second }

    @Test
    fun `class spec resolves the first exact candidate that exists`() {
        val spec = Targets.classes("t.class") { exact("com.example.Missing", Fixtures.SAMPLE, Fixtures.OTHER) }
        val result = targets().resolve(spec)
        assertEquals(Resolution.EXACT, result.resolution)
        assertTrue(result.found)
        assertEquals(listOf<Class<*>>(Fixtures.Sample::class.java), result.values)
        assertSame(Fixtures.Sample::class.java, result.value)
        assertEquals(Fixtures.SAMPLE, result.detail)
        assertEquals("t.class: EXACT ${Fixtures.SAMPLE}", result.toString())
    }

    @Test
    fun `multiple class spec keeps every exact candidate that exists`() {
        val spec = Targets.classes("t.classes") {
            exact(Fixtures.SAMPLE, "com.example.Missing", Fixtures.OTHER)
            multiple = true
        }
        val result = targets().resolve(spec)
        assertEquals(listOf(Fixtures.Sample::class.java, Fixtures.Other::class.java), result.values)
        assertEquals("${Fixtures.SAMPLE}, ${Fixtures.OTHER}", result.detail)
    }

    @Test
    fun `nothing found is MISSING, logged as a warning and never thrown`() {
        val spec = Targets.classes("t.missing") {
            exact("com.example.Missing")
            discover { packages("com.example") }
        }
        val result = targets().resolve(spec)
        assertEquals(Resolution.MISSING, result.resolution)
        assertFalse(result.found)
        assertNull(result.value)
        assertTrue(result.values.isEmpty())
        assertTrue("com.example.Missing (not found)" in result.detail, result.detail)
        assertTrue(messages(LogLevel.WARN).any { it.startsWith("t.missing: MISSING") }, log.entries.toString())

        val exactOnly = targets().resolve(Targets.classes("t.exactOnly") { exact("com.example.Missing") })
        assertTrue(exactOnly.detail.endsWith("; no discovery"), exactOnly.detail)
    }

    @Test
    fun `method spec matches name, parameters and return type in exact classes`() {
        val hostTargets = targets()
        val twoArgs = hostTargets.resolve(
            Targets.method("t.run2") {
                exactIn(Fixtures.SAMPLE)
                name = "run"
                params(String::class.java, Boolean::class.javaPrimitiveType)
                returns(Int::class.javaPrimitiveType)
            },
        )
        val method = assertNotNull(twoArgs.value)
        assertEquals(listOf(String::class.java, java.lang.Boolean.TYPE), method.parameterTypes.toList())
        assertEquals(2, method.invoke(Fixtures.Sample(), "x", true))
        assertEquals("${Fixtures.SAMPLE}#run(String, boolean)", twoArgs.detail)

        val script = hostTargets.resolve(
            Targets.method("t.script") {
                exactIn("com.example.Missing", Fixtures.SAMPLE)
                name = "loadScriptFromFile"
                params(String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                returns(Void.TYPE)
                concreteOnly = true
            },
        )
        assertEquals(Resolution.EXACT, script.resolution)
        assertEquals("${Fixtures.SAMPLE}#loadScriptFromFile(String, String, boolean)", script.detail)

        val wrongParams = hostTargets.resolve(
            Targets.method("t.wrongParams") {
                exactIn(Fixtures.SAMPLE)
                name = "run"
                params(String::class.java, Int::class.javaPrimitiveType)
            },
        )
        assertEquals(Resolution.MISSING, wrongParams.resolution)
        assertTrue("${Fixtures.SAMPLE} (no #run(String, int))" in wrongParams.detail, wrongParams.detail)

        val wrongReturn = hostTargets.resolve(
            Targets.method("t.wrongReturn") {
                exactIn(Fixtures.SAMPLE)
                name = "run"
                params(String::class.java)
                returns(Void.TYPE)
            },
        )
        assertEquals(Resolution.MISSING, wrongReturn.resolution)
    }

    @Test
    fun `anyParams returns every overload in a stable order or the first with a warning`() {
        val all = targets().resolve(
            Targets.method("t.overloads") {
                exactIn(Fixtures.SAMPLE)
                name = "run"
                anyParams()
                multiple = true
            },
        )
        assertEquals(listOf(1, 2), all.values.map { it.parameterTypes.size })

        val first = targets().resolve(
            Targets.method("t.first") {
                exactIn(Fixtures.SAMPLE)
                name = "run"
                anyParams()
            },
        )
        assertEquals(1, first.values.size)
        assertEquals(1, first.value?.parameterTypes?.size)
        assertTrue(messages(LogLevel.WARN).any { "2 members of ${Fixtures.SAMPLE} match" in it }, log.entries.toString())
    }

    @Test
    fun `concreteOnly skips abstract declarations`() {
        fun spec(id: String, concrete: Boolean, multiple: Boolean) = Targets.method(id) {
            exactIn(Fixtures.BASE, Fixtures.SAMPLE)
            name = "run"
            params(String::class.java)
            concreteOnly = concrete
            this.multiple = multiple
        }
        val hostTargets = targets()
        val concrete = hostTargets.resolve(spec("t.concrete", concrete = true, multiple = true))
        assertEquals(listOf<Class<*>>(Fixtures.Sample::class.java), concrete.values.map(Method::getDeclaringClass))

        val any = hostTargets.resolve(spec("t.abstract", concrete = false, multiple = true))
        assertEquals(
            listOf(Fixtures.Base::class.java, Fixtures.Sample::class.java),
            any.values.map(Method::getDeclaringClass),
        )
    }

    @Test
    fun `constructor and field specs resolve declared members`() {
        val hostTargets = targets()
        val constructors = hostTargets.resolve(
            Targets.constructor("t.constructors") {
                exactIn(Fixtures.OTHER)
                anyParams()
                multiple = true
            },
        )
        assertEquals(3, constructors.values.size)
        assertEquals("${Fixtures.OTHER}#<init>()", constructors.detail.substringBefore(", "))

        val byString = hostTargets.resolve(
            Targets.constructor("t.constructor") {
                exactIn(Fixtures.OTHER)
                params(String::class.java)
            },
        )
        assertEquals(4, (assertNotNull(byString.value).newInstance("four") as Fixtures.Other).size)

        val count = hostTargets.resolve(
            Targets.field("t.count") {
                exactIn(Fixtures.SAMPLE)
                name = "count"
                type(Int::class.javaPrimitiveType)
            },
        )
        assertEquals("${Fixtures.SAMPLE}#count", count.detail)
        assertEquals(0, assertNotNull(count.value).get(Fixtures.Sample()))

        val wrongType = hostTargets.resolve(
            Targets.field("t.label") {
                exactIn(Fixtures.SAMPLE)
                name = "label"
                type(Int::class.javaPrimitiveType)
            },
        )
        assertEquals(Resolution.MISSING, wrongType.resolution)
    }

    @Test
    fun `results are memoized per id and reported in order`() {
        val hostTargets = targets()
        val spec = Targets.classes("t.memo") { exact(Fixtures.SAMPLE) }
        val first = hostTargets.resolve(spec)
        assertSame(first, hostTargets.resolve(spec))
        assertSame(first, hostTargets.resolve(Targets.classes("t.memo") { exact(Fixtures.OTHER) }))
        hostTargets.resolve(Targets.classes("t.gone") { exact("com.example.Missing") })

        // A reused id of another kind is resolved on its own and not reported.
        val method = hostTargets.resolve(
            Targets.method("t.memo") {
                exactIn(Fixtures.SAMPLE)
                name = "run"
                params(String::class.java)
            },
        )
        assertEquals(Resolution.EXACT, method.resolution)
        assertTrue(log.errors().any { "t.memo" in it }, log.entries.toString())

        assertEquals(listOf("t.memo", "t.gone"), hostTargets.report().map { it.spec.id })
        assertEquals("targets: 1 exact, 0 cached, 0 discovered, 1 missing", hostTargets.summary())
    }

    @Test
    fun `forced discovery skips the exact step for listed ids`() {
        val file = paths.forceDiscoveryFile.apply { checkNotNull(parentFile).mkdirs() }
        file.writeText("\uFEFFt.forced\r\n\r\n  other.id  \r\n")
        val hostTargets = targets(forceDiscoveryFile = file)
        val forced = hostTargets.resolve(
            Targets.classes("t.forced") {
                exact(Fixtures.SAMPLE)
                discover { packages("io.github.trickhook.shadowzap.hook.targets") }
            },
        )
        assertEquals(Resolution.MISSING, forced.resolution)
        assertTrue(forced.detail.startsWith("exact skipped (forced discovery)"), forced.detail)
        assertEquals(Resolution.EXACT, hostTargets.resolve(Targets.classes("t.normal") { exact(Fixtures.SAMPLE) }).resolution)

        file.writeText("*\n")
        val all = targets(forceDiscoveryFile = file)
        assertEquals(Resolution.MISSING, all.resolve(Targets.classes("t.normal") { exact(Fixtures.SAMPLE) }).resolution)

        // Release builds pass no file: the switch is ignored even when it exists.
        val release = targets(forceDiscoveryFile = null)
        assertEquals(Resolution.EXACT, release.resolve(Targets.classes("t.normal") { exact(Fixtures.SAMPLE) }).resolution)
    }

    @Test
    fun `forced discovery file parsing`() {
        val parsed = ForcedDiscovery.parse("\uFEFF a.b \r\n\r\nc.d\n")
        assertFalse(parsed.all)
        assertEquals(setOf("a.b", "c.d"), parsed.ids)
        assertTrue(parsed.covers("a.b"))
        assertFalse(parsed.covers("x"))
        assertTrue(ForcedDiscovery.parse("*").covers("anything"))
        assertTrue(ForcedDiscovery.parse("  \n").isEmpty)
        assertTrue(ForcedDiscovery.read(File(dataDir, "absent"), log).isEmpty)
        assertTrue(ForcedDiscovery.read(null, log).isEmpty)
    }

    @Test
    fun `resolved target values must agree with the resolution`() {
        val spec = Targets.classes("t.invariant") { exact(Fixtures.SAMPLE) }
        assertFailsWith<IllegalArgumentException> { ResolvedTarget(spec, emptyList(), Resolution.EXACT, "") }
        assertFailsWith<IllegalArgumentException> {
            ResolvedTarget(spec, listOf(Fixtures.Sample::class.java), Resolution.MISSING, "")
        }
    }

    @Test
    fun `require returns the first value or throws naming the spec`() {
        val targets = targets()
        assertSame(Fixtures.Sample::class.java, targets.require(Targets.classes("t.required") { exact(Fixtures.SAMPLE) }))
        val missing = Targets.classes("t.requiredMissing") { exact("com.example.Missing") }
        val error = assertFailsWith<HookTargetNotFoundException> { targets.require(missing) }
        assertEquals(targets.resolve(missing).toString(), error.message)
        assertTrue(error.message!!.startsWith("t.requiredMissing: MISSING exact: com.example.Missing (not found)"))
    }

    @Test
    fun `kernel exposes targets and the boot line carries the summary`() {
        val lines = mutableListOf<LogEntry>()
        val feature = object : Feature {
            override val id: String = "uses-targets"

            override fun install(ctx: FeatureContext) {
                ctx.targets.resolve(Targets.classes("t.boot") { exact(Fixtures.SAMPLE) })
                ctx.targets.resolve(Targets.classes("t.bootMissing") { exact("com.example.Missing") })
            }

            override fun isEnabled(env: Env): Boolean = true
        }
        val kernel = assertNotNull(
            Bootstrap.start(
                TestEnvs.env(TestEnvs.tempDir()),
                FakeHookBackend(),
                features = { listOf(feature) },
                extraSinks = listOf(LogSink { entry, _ -> synchronized(lines) { lines += entry } }),
            ),
        )
        assertEquals(listOf("t.boot", "t.bootMissing"), kernel.targets.report().map { it.spec.id })
        val bootLine = lines.last { it.logger == "boot" }.message
        assertTrue(bootLine.startsWith("Boot via "), bootLine)
        assertTrue(bootLine.endsWith("; targets: 1 exact, 0 cached, 0 discovered, 1 missing"), bootLine)
        assertTrue(
            lines.any { it.logger == "targets" && it.level == LogLevel.WARN && it.message.startsWith("t.bootMissing: MISSING") },
        )
    }
}
