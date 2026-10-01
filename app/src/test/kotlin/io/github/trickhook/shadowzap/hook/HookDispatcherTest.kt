package io.github.trickhook.shadowzap.hook

import io.github.trickhook.shadowzap.api.hooks.HookCall
import io.github.trickhook.shadowzap.api.hooks.HookCallback
import io.github.trickhook.shadowzap.api.hooks.HookPriority
import io.github.trickhook.shadowzap.api.hooks.after
import io.github.trickhook.shadowzap.api.hooks.before
import io.github.trickhook.shadowzap.api.hooks.replace
import io.github.trickhook.shadowzap.testing.FakeHookBackend
import io.github.trickhook.shadowzap.testing.RecordingLogger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("unused")
private class Target {
    val marker: Int = 1

    fun compute(x: Int): Int = x * 2
    fun other(): String = "other"
    fun fail(): String = throw IllegalStateException("original failed")
}

class HookDispatcherTest {
    private lateinit var backend: FakeHookBackend
    private lateinit var log: RecordingLogger
    private lateinit var hooks: HookDispatcher
    private val target = Target()
    private val compute = Target::class.java.getDeclaredMethod("compute", Int::class.javaPrimitiveType)
    private val fail = Target::class.java.getDeclaredMethod("fail")
    private val other = Target::class.java.getDeclaredMethod("other")

    @BeforeTest
    fun setUp() {
        backend = FakeHookBackend()
        log = RecordingLogger()
        hooks = HookDispatcher(backend, log)
    }

    private fun callCompute(x: Int, phased: Boolean = false): Any? {
        val original: (Array<Any?>) -> Any? = { args -> target.compute(args[0] as Int) }
        return if (phased) {
            backend.callPhased(compute, target, arrayOf(x), original)
        } else {
            backend.call(compute, target, arrayOf(x), original)
        }
    }

    private fun recorder(name: String, events: MutableList<String>) = object : HookCallback {
        override fun before(call: HookCall) {
            events += "before:$name"
        }

        override fun after(call: HookCall) {
            events += "after:$name"
        }
    }

    @Test
    fun `installs one framework hook per member`() {
        repeat(5) { hooks.before(compute) {} }
        hooks.before(other) {}
        assertEquals(2, backend.installCount)
        assertEquals(5, hooks.callbackCount(compute))
    }

    @Test
    fun `before callbacks run by descending priority and after callbacks in reverse`() {
        for (phased in listOf(false, true)) {
            setUp()
            val events = mutableListOf<String>()
            hooks.hook(compute, HookPriority.LOW, recorder("low", events))
            hooks.hook(compute, HookPriority.HIGHEST, recorder("highest", events))
            hooks.hook(compute, HookPriority.DEFAULT, recorder("default-1", events))
            hooks.hook(compute, HookPriority.DEFAULT, recorder("default-2", events))

            assertEquals(42, callCompute(21, phased))
            assertEquals(
                listOf(
                    "before:highest", "before:default-1", "before:default-2", "before:low",
                    "after:low", "after:default-2", "after:default-1", "after:highest",
                ),
                events,
            )
        }
    }

    @Test
    fun `setting a result in before skips the original and lower-priority befores`() {
        for (phased in listOf(false, true)) {
            setUp()
            val events = mutableListOf<String>()
            var originalRan = false
            hooks.hook(compute, HookPriority.HIGH, recorder("high", events))
            hooks.hook(compute, HookPriority.DEFAULT, object : HookCallback {
                override fun before(call: HookCall) {
                    events += "before:decider"
                    call.result = 7
                }

                override fun after(call: HookCall) {
                    events += "after:decider"
                }
            })
            hooks.hook(compute, HookPriority.LOW, recorder("low", events))

            val result = if (phased) {
                backend.callPhased(compute, target, arrayOf(1)) { originalRan = true; 0 }
            } else {
                backend.call(compute, target, arrayOf(1)) { originalRan = true; 0 }
            }

            assertEquals(7, result)
            assertFalse(originalRan)
            assertEquals(listOf("before:high", "before:decider", "after:decider", "after:high"), events)
        }
    }

    @Test
    fun `after can replace the result or the throwable`() {
        hooks.after(compute) { call -> call.result = (call.result as Int) + 1 }
        assertEquals(11, callCompute(5))

        hooks.after(compute, HookPriority.HIGHEST) { call -> call.throwable = IllegalArgumentException("nope") }
        assertFailsWith<IllegalArgumentException> { callCompute(5) }
    }

    @Test
    fun `after sees the original throwable and may swallow it`() {
        var seen: Throwable? = null
        hooks.after(fail) { call ->
            seen = call.throwable
            call.result = "recovered"
        }
        val result = backend.call(fail, target, emptyArray()) { target.fail() }
        assertEquals("recovered", result)
        assertTrue(seen is IllegalStateException)
    }

    @Test
    fun `argument changes in before reach the original`() {
        hooks.before(compute) { call -> call.args[0] = 100 }
        assertEquals(200, callCompute(1))
        assertEquals(200, callCompute(1, phased = true))
    }

    @Test
    fun `throwing callbacks are isolated and their changes rolled back`() {
        val events = mutableListOf<String>()
        hooks.hook(compute, HookPriority.HIGH, object : HookCallback {
            override fun before(call: HookCall) {
                call.result = 999
                throw RuntimeException("broken before")
            }
        })
        hooks.hook(compute, HookPriority.DEFAULT, recorder("healthy", events))
        hooks.hook(compute, HookPriority.LOW, object : HookCallback {
            override fun after(call: HookCall) {
                call.result = -1
                throw RuntimeException("broken after")
            }
        })

        assertEquals(10, callCompute(5))
        assertEquals(listOf("before:healthy", "after:healthy"), events)
        assertEquals(2, log.errors().size)
    }

    @Test
    fun `unhook removes exactly one callback and is idempotent`() {
        val events = mutableListOf<String>()
        val shared = recorder("shared", events)
        val first = hooks.hook(compute, HookPriority.DEFAULT, shared)
        val second = hooks.hook(compute, HookPriority.DEFAULT, shared)

        first.unhook()
        first.unhook()
        assertFalse(first.isActive)
        assertTrue(second.isActive)

        callCompute(1)
        assertEquals(listOf("before:shared", "after:shared"), events)
        assertEquals(1, hooks.callbackCount(compute))
        assertEquals(1, backend.installCount)
    }

    @Test
    fun `unhooking during a call does not disturb the running call`() {
        val events = mutableListOf<String>()
        lateinit var later: io.github.trickhook.shadowzap.api.hooks.HookHandle
        hooks.before(compute, HookPriority.HIGH) { later.unhook() }
        later = hooks.hook(compute, HookPriority.LOW, recorder("later", events))

        callCompute(1)
        assertEquals(listOf("before:later", "after:later"), events)
        events.clear()
        callCompute(1)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `replace returns the block result without running the original`() {
        var originalRan = false
        hooks.replace(compute) { call -> (call.args[0] as Int) + 1000 }
        val result = backend.call(compute, target, arrayOf(1)) { originalRan = true; 0 }
        assertEquals(1001, result)
        assertFalse(originalRan)
    }

    @Test
    fun `a refused framework hook propagates and registers nothing`() {
        backend.refuseWith = IllegalStateException("framework says no")
        assertFailsWith<IllegalStateException> { hooks.before(compute) {} }
        assertEquals(0, hooks.installedMemberCount())

        backend.refuseWith = null
        hooks.before(compute) {}
        assertEquals(1, hooks.installedMemberCount())
    }

    @Test
    fun `invokeOriginal goes through the backend and unwraps exceptions`() {
        assertEquals(8, hooks.invokeOriginal(compute, target, 4))
        val error = assertFailsWith<IllegalStateException> { hooks.invokeOriginal(fail, target) }
        assertEquals("original failed", error.message)
    }

    @Test
    fun `non executable members are rejected`() {
        val field = Target::class.java.getDeclaredField("marker")
        assertFailsWith<IllegalArgumentException> { hooks.before(field) {} }
    }

    @Test
    fun `call state exposes receiver and member`() {
        var seenThis: Any? = null
        var seenMember: Any? = null
        hooks.before(compute) { call ->
            seenThis = call.thisObject
            seenMember = call.member
        }
        callCompute(3)
        assertSame(target, seenThis)
        assertEquals(compute, seenMember)
    }

    @Test
    fun `framework refusals of any type surface as IllegalStateException`() {
        backend.refuseWith = NoSuchMethodError("native hook failed")
        val error = assertFailsWith<IllegalStateException> { hooks.before(compute) {} }
        assertTrue(error.cause is NoSuchMethodError)
        assertEquals(0, hooks.installedMemberCount())

        backend.refuseWith = IllegalArgumentException("not hookable")
        assertFailsWith<IllegalArgumentException> { hooks.before(compute) {} }
    }

    @Test
    fun `a null decided for a primitive return type runs the original instead`() {
        for (phased in listOf(false, true)) {
            setUp()
            hooks.before(compute) { call -> call.result = null }
            assertEquals(6, callCompute(3, phased))
            assertTrue(log.errors().any { "invalid outcome" in it })
        }
    }

    @Test
    fun `a wrong-typed result is never returned`() {
        for (phased in listOf(false, true)) {
            setUp()
            var originalRan = false
            val original: (Array<Any?>) -> Any? = { originalRan = true; target.other() }
            hooks.replace(other) { Unit }
            val result = if (phased) {
                backend.callPhased(other, target, emptyArray(), original)
            } else {
                backend.call(other, target, emptyArray(), original)
            }
            assertEquals("other", result)
            assertTrue(originalRan)
        }
    }

    @Test
    fun `an invalid result set in after falls back to the previous outcome`() {
        for (phased in listOf(false, true)) {
            setUp()
            hooks.after(compute) { call -> call.result = "not an int" }
            assertEquals(8, callCompute(4, phased))
            assertTrue(log.errors().any { "invalid result" in it })

            setUp()
            hooks.before(compute) { call -> call.result = 100 }
            hooks.after(compute, HookPriority.HIGHEST) { call -> call.result = null }
            assertEquals(100, callCompute(4, phased))
        }
    }

    @Test
    fun `valid replacements and boxed primitives pass the result check`() {
        // The after callback has the higher priority, so its (empty) before runs before the decision.
        hooks.before(compute) { call -> call.result = 41 }
        hooks.after(compute, HookPriority.HIGH) { call -> call.result = (call.result as Int) + 1 }
        assertEquals(42, callCompute(1))
        assertEquals(42, callCompute(1, phased = true))
        assertTrue(log.errors().isEmpty())
    }
}
