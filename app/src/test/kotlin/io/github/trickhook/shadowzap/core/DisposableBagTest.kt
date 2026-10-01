package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.api.host.Disposable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisposableBagTest {
    @Test
    fun `disposes newest first, isolates failures and is idempotent`() {
        val order = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        val bag = DisposableBag { errors += it }
        bag.add(Disposable { order += "first" })
        bag.add(Disposable { error("broken") })
        bag.add(Disposable { order += "third" })

        bag.dispose()
        bag.dispose()
        assertEquals(listOf("third", "first"), order)
        assertEquals(1, errors.size)
        assertTrue(bag.isDisposed)
    }

    @Test
    fun `items added after disposal are released immediately`() {
        val bag = DisposableBag()
        bag.dispose()
        var released = false
        bag.add(Disposable { released = true })
        assertTrue(released)
    }

    @Test
    fun `removed items are not disposed`() {
        val bag = DisposableBag()
        var released = false
        val item = bag.add(Disposable { released = true })
        bag.remove(item)
        bag.dispose()
        assertTrue(!released)
    }

    @Test
    fun `once disposable runs once`() {
        var count = 0
        val once = OnceDisposable { count++ }
        once.dispose()
        once.dispose()
        assertEquals(1, count)
        assertTrue(once.isDisposed)
    }
}
