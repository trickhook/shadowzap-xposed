package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.api.host.Disposable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe collection of [Disposable]s released together, newest first. Once disposed, anything added later is
 * released immediately, so an owner that already stopped cannot leak new resources.
 */
internal class DisposableBag(private val onError: (Throwable) -> Unit = {}) : Disposable {
    private val items = ArrayList<Disposable>()
    private var disposed = false

    val isDisposed: Boolean get() = synchronized(items) { disposed }

    /** Adds [item] and returns it; disposes it right away when the bag is already disposed. */
    fun <T : Disposable> add(item: T): T {
        if (item === Disposable.NONE) return item
        val accepted = synchronized(items) {
            if (!disposed) items += item
            !disposed
        }
        if (!accepted) safeDispose(item)
        return item
    }

    /** Forgets [item] without disposing it (it was released by its owner). */
    fun remove(item: Disposable) {
        synchronized(items) { items.remove(item) }
    }

    override fun dispose() {
        val toRelease = synchronized(items) {
            if (disposed) return
            disposed = true
            items.toList().also { items.clear() }
        }
        toRelease.asReversed().forEach(::safeDispose)
    }

    private fun safeDispose(item: Disposable) {
        try {
            item.dispose()
        } catch (t: Throwable) {
            onError(t)
        }
    }
}

/** A [Disposable] that runs [block] exactly once. */
internal class OnceDisposable(private val block: () -> Unit) : Disposable {
    private val done = AtomicBoolean(false)

    val isDisposed: Boolean get() = done.get()

    override fun dispose() {
        if (done.compareAndSet(false, true)) block()
    }
}

/**
 * A replaceable service with a fallback: subsystems install their implementation, and disposing the returned
 * handle restores whatever was installed before (or the fallback).
 */
internal class Slot<T : Any>(private val fallback: T) {
    private val stack = ArrayList<T>()

    /** The most recently installed implementation, or the fallback. */
    fun get(): T = synchronized(stack) { stack.lastOrNull() ?: fallback }

    fun install(implementation: T): Disposable {
        synchronized(stack) { stack += implementation }
        return OnceDisposable { synchronized(stack) { stack.remove(implementation) } }
    }
}
