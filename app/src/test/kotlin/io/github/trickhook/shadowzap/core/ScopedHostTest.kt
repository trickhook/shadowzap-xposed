package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.testing.TestEnvs
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("unused")
private class Subject {
    fun run(): Int = 1
}

class ScopedHostTest {
    private val method = Subject::class.java.getDeclaredMethod("run")

    @Test
    fun `dispose releases hooks, subscriptions and coroutines`() = runBlocking<Unit> {
        val kernel = TestEnvs.kernel()
        val host = kernel.newScopedHost("feature:test")
        val survivor = kernel.newScopedHost("feature:survivor")
        var survivorDelivered = false
        survivor.onContext { survivorDelivered = true }

        host.hooks.before(method) {}
        var contextDelivered = false
        host.onContext { contextDelivered = true }
        val job: Job = host.coroutineScope.launch { awaitCancellation() }

        assertEquals(1, kernel.hooks.callbackCount(method))

        host.dispose()
        job.join()

        assertEquals(0, kernel.hooks.callbackCount(method))
        assertTrue(job.isCancelled)

        kernel.lifecycle.attachApplication(android.app.Application())
        assertFalse(contextDelivered)
        assertTrue(survivorDelivered)

        assertFailsWith<IllegalStateException> { host.hooks.before(method) {} }
    }

    @Test
    fun `an individual unhook is forgotten by the owner`() {
        val kernel = TestEnvs.kernel()
        val host = kernel.newScopedHost("feature:test")
        val handle = host.hooks.before(method) {}
        handle.unhook()

        host.dispose()
        assertEquals(0, kernel.hooks.callbackCount(method))
    }

    @Test
    fun `owners do not affect each other`() {
        val kernel = TestEnvs.kernel()
        val first = kernel.newScopedHost("feature:first")
        val second = kernel.newScopedHost("feature:second")
        first.hooks.before(method) {}
        second.hooks.before(method) {}

        first.dispose()
        assertEquals(1, kernel.hooks.callbackCount(method))
        assertFalse(second.isDisposed)
    }
}
