package io.github.trickhook.shadowzap.core

import android.os.Handler
import android.os.Looper
import io.github.trickhook.shadowzap.api.host.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Root coroutine scope of the loader. It lives as long as the process, uses a supervisor job so one failing
 * coroutine never cancels unrelated work, and logs uncaught exceptions instead of crashing the host.
 */
internal class ModuleScope(private val log: Logger) : CoroutineScope {
    private val job: Job = SupervisorJob()

    override val coroutineContext: CoroutineContext =
        job + Dispatchers.Default + CoroutineName("shadowzap") + handlerFor(log)

    /** Bounded dispatcher for blocking disk and network work. */
    val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(IO_PARALLELISM, "shadowzap-io")

    /**
     * The host's main thread. Created on first use without going through `Dispatchers.Main`, whose service lookup
     * is unreliable inside a module class loader.
     */
    val main: CoroutineDispatcher by lazy {
        Handler(Looper.getMainLooper()).asCoroutineDispatcher("shadowzap-main")
    }

    /** A dispatcher that runs one task at a time, for confining mutable state to a single logical thread. */
    fun serial(name: String): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1, name)

    /**
     * A child scope for one owner (a feature or a plugin). Cancelling it cancels only its own coroutines;
     * uncaught exceptions are logged to [ownerLog].
     */
    fun child(name: String, ownerLog: Logger): CoroutineScope =
        CoroutineScope(SupervisorJob(job) + Dispatchers.Default + CoroutineName(name) + handlerFor(ownerLog))

    private fun handlerFor(logger: Logger) = CoroutineExceptionHandler { context, throwable ->
        logger.e("Uncaught exception in coroutine ${context[CoroutineName]?.name ?: "?"}", throwable)
    }

    private companion object {
        const val IO_PARALLELISM = 16
    }
}
