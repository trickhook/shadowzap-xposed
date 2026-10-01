package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.testing.TestEnvs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("unused")
private class Hooked {
    fun work(): Int = 1
}

class FeatureRunnerTest {
    private val work = Hooked::class.java.getDeclaredMethod("work")

    private class TestFeature(
        override val id: String,
        private val enabled: Boolean = true,
        private val body: (FeatureContext) -> Unit = {},
    ) : Feature {
        override fun isEnabled(env: Env): Boolean = enabled
        override fun install(ctx: FeatureContext) = body(ctx)
    }

    @Test
    fun `features install in order and failures are isolated`() {
        val kernel = TestEnvs.kernel()
        val order = mutableListOf<String>()
        val report = FeatureRunner(kernel).run(
            listOf(
                TestFeature("a") { order += "a" },
                TestFeature("broken") { order += "broken"; error("install failed") },
                TestFeature("skipped", enabled = false) { order += "skipped" },
                TestFeature("c") { order += "c" },
            ),
        )

        assertEquals(listOf("a", "broken", "c"), order)
        assertEquals(
            listOf(
                FeatureOutcome.Status.INSTALLED,
                FeatureOutcome.Status.FAILED,
                FeatureOutcome.Status.SKIPPED,
                FeatureOutcome.Status.INSTALLED,
            ),
            report.features.map { it.status },
        )
        assertEquals(listOf("broken"), report.failed.map { it.id })
        assertSame(report, kernel.bootReport)
        assertTrue(report.summary().contains("broken=failed"))
    }

    @Test
    fun `a failed feature releases its hooks and seam registrations`() {
        val kernel = TestEnvs.kernel()
        FeatureRunner(kernel).run(
            listOf(
                TestFeature("half-installed") { ctx ->
                    ctx.hooks.before(work) {}
                    ctx.provideRecovery { }
                    error("fails after registering")
                },
                TestFeature("healthy") { ctx -> ctx.hooks.before(work) {} },
            ),
        )

        assertEquals(1, kernel.hooks.callbackCount(work), "only the healthy feature's hook remains")
        assertTrue(kernel.recovery.get() is io.github.trickhook.shadowzap.core.seams.BasicRecoveryPresenter)
    }

    @Test
    fun `after-boot steps run after every feature, isolated, and not for failed features`() {
        val kernel = TestEnvs.kernel()
        val events = mutableListOf<String>()
        val report = FeatureRunner(kernel).run(
            listOf(
                TestFeature("first") { ctx ->
                    events += "install:first"
                    ctx.afterBoot { events += "after:first" }
                    ctx.afterBoot { error("after-boot failure") }
                },
                TestFeature("failing") { ctx ->
                    ctx.afterBoot { events += "after:failing" }
                    error("install failed")
                },
                TestFeature("last") { ctx ->
                    events += "install:last"
                    ctx.afterBoot { events += "after:last" }
                },
            ),
        )

        assertEquals(listOf("install:first", "install:last", "after:first", "after:last"), events)
        assertEquals(listOf("first"), report.afterBootFailures.map { it.first })
    }

    @Test
    fun `the ordered feature list has unique ids and starts with the kernel features`() {
        val features = Features.ordered()
        val ids = features.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate feature ids: $ids")
        assertEquals(listOf("lifecycle", "whatsapp-info", "settings-entry"), ids.take(3))
        assertTrue(features.all { it.id.isNotBlank() })
    }

    @Test
    fun `Bootstrap never throws and reports every feature`() {
        val env = TestEnvs.env(TestEnvs.tempDir())
        val kernel = Bootstrap.start(
            env,
            io.github.trickhook.shadowzap.testing.FakeHookBackend(),
            features = { listOf(TestFeature("ok"), TestFeature("bad") { error("bad") }) },
        )
        val report = kernel?.bootReport
        assertEquals(listOf("ok", "bad"), report?.features?.map { it.id })
        assertSame(kernel, Kernel.current)
    }
}
