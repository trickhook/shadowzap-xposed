package io.github.trickhook.shadowzap.core

import android.os.Handler
import android.os.Looper

/** Helpers for the host's main (UI) thread. */
internal object MainThread {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    val isCurrent: Boolean get() = Looper.myLooper() == Looper.getMainLooper()

    /** Runs [block] on the main thread: inline when already on it, otherwise posted. */
    fun run(block: () -> Unit) {
        if (isCurrent) block() else handler.post(block)
    }

    /** Always posts [block] to the main thread's queue. */
    fun post(block: () -> Unit) {
        handler.post(block)
    }
}
