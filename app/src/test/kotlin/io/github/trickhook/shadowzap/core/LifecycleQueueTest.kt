package io.github.trickhook.shadowzap.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LatchTest {
    private val errors = mutableListOf<Throwable>()
    private val latch = Latch<String> { errors += it }

    @Test
    fun `queued subscribers run once, in order, when the value arrives`() {
        val seen = mutableListOf<String>()
        latch.subscribe { seen += "a:$it" }
        latch.subscribe { seen += "b:$it" }
        assertTrue(seen.isEmpty())

        assertTrue(latch.offer("ctx"))
        assertEquals(listOf("a:ctx", "b:ctx"), seen)
        assertEquals(0, latch.pendingCount())
    }

    @Test
    fun `the first value is never overwritten`() {
        assertTrue(latch.offer("application"))
        assertFalse(latch.offer("service"))
        assertEquals("application", latch.get())
    }

    @Test
    fun `late subscribers run immediately`() {
        latch.offer("ctx")
        var seen: String? = null
        latch.subscribe { seen = it }
        assertEquals("ctx", seen)
    }

    @Test
    fun `a throwing subscriber does not drop the others`() {
        val seen = mutableListOf<String>()
        latch.subscribe { seen += "first" }
        latch.subscribe { throw IllegalStateException("boom") }
        latch.subscribe { seen += "third" }

        latch.offer("ctx")
        assertEquals(listOf("first", "third"), seen)
        assertEquals(1, errors.size)
    }

    @Test
    fun `disposed subscribers never run`() {
        var ran = false
        val subscription = latch.subscribe { ran = true }
        subscription.dispose()
        latch.offer("ctx")
        assertFalse(ran)
        assertEquals(0, latch.pendingCount())
    }

    @Test
    fun `subscribing from inside a subscriber runs immediately`() {
        val seen = mutableListOf<String>()
        latch.subscribe {
            seen += "outer"
            latch.subscribe { seen += "inner" }
        }
        latch.offer("ctx")
        assertEquals(listOf("outer", "inner"), seen)
    }
}

class ActivityTrackerTest {
    private class FakeActivity(val name: String, var usable: Boolean = true)

    private val errors = mutableListOf<Throwable>()
    private val posted = ArrayDeque<() -> Unit>()
    private var deferDispatch = false

    private val tracker = ActivityTracker<FakeActivity>(
        isUsable = { it.usable },
        dispatch = { block -> if (deferDispatch) posted.addLast(block) else block() },
        onError = { errors += it },
    )

    @Test
    fun `subscribers wait for the first usable resumed activity`() {
        val seen = mutableListOf<String>()
        tracker.subscribe { seen += it.name }
        assertTrue(seen.isEmpty())

        tracker.onResumed(FakeActivity("finishing", usable = false))
        assertTrue(seen.isEmpty())
        assertNull(tracker.current())

        tracker.onResumed(FakeActivity("main"))
        assertEquals(listOf("main"), seen)
    }

    @Test
    fun `current activity is forgotten when destroyed`() {
        val activity = FakeActivity("main")
        tracker.onResumed(activity)
        assertSame(activity, tracker.current())

        tracker.onDestroyed(FakeActivity("other"))
        assertSame(activity, tracker.current())

        tracker.onDestroyed(activity)
        assertNull(tracker.current())
    }

    @Test
    fun `a current activity that stops being usable is not returned`() {
        val activity = FakeActivity("main")
        tracker.onResumed(activity)
        activity.usable = false
        assertNull(tracker.current())
    }

    @Test
    fun `immediate delivery rechecks usability on the dispatch thread`() {
        val activity = FakeActivity("main")
        tracker.onResumed(activity)

        deferDispatch = true
        val seen = mutableListOf<String>()
        tracker.subscribe { seen += it.name }
        activity.usable = false
        posted.removeFirst().invoke()
        assertTrue(seen.isEmpty())
        assertEquals(1, tracker.pendingCount())

        deferDispatch = false
        tracker.onResumed(FakeActivity("next"))
        assertEquals(listOf("next"), seen)
    }

    @Test
    fun `subscribers are isolated and disposable`() {
        val seen = mutableListOf<String>()
        tracker.subscribe { throw IllegalStateException("boom") }
        val cancelled = tracker.subscribe { seen += "cancelled" }
        tracker.subscribe { seen += "ok" }
        cancelled.dispose()

        tracker.onResumed(FakeActivity("main"))
        assertEquals(listOf("ok"), seen)
        assertEquals(1, errors.size)
    }
}
