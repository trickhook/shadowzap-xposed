package io.github.trickhook.shadowzap.core

import android.util.Log
import io.github.trickhook.shadowzap.api.host.Disposable
import io.github.trickhook.shadowzap.api.host.LogLevel
import io.github.trickhook.shadowzap.api.host.Logger
import java.util.concurrent.CopyOnWriteArrayList

/** One recorded log entry, kept in the ring buffer for the recovery UI. */
internal data class LogEntry(
    val timeMillis: Long,
    val level: LogLevel,
    val logger: String,
    val message: String,
    /** Stack trace of the attached throwable, truncated, or `null`. */
    val stackTrace: String?,
)

/** Destination for log entries besides the ring buffer. */
internal fun interface LogSink {
    fun write(entry: LogEntry, throwable: Throwable?)
}

/** Fixed-size, thread-safe buffer of the most recent entries. */
internal class LogRing(private val capacity: Int) {
    private val entries = ArrayDeque<LogEntry>(capacity)

    fun add(entry: LogEntry) = synchronized(entries) {
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(entry)
    }

    fun snapshot(): List<LogEntry> = synchronized(entries) { entries.toList() }

    fun clear() = synchronized(entries) { entries.clear() }
}

/**
 * Central log router: every [Logger] handed out by the kernel writes through here to logcat, to extra sinks (such
 * as the Xposed framework log) and to the [ring] buffer.
 */
internal class LogHub(
    private val minLevel: LogLevel,
    ringCapacity: Int = 512,
) {
    val ring = LogRing(ringCapacity)
    private val sinks = CopyOnWriteArrayList<LogSink>(listOf(AndroidLogSink))

    fun isLoggable(level: LogLevel): Boolean = level >= minLevel

    fun logger(name: String): Logger = KernelLogger(name, this)

    fun addSink(sink: LogSink): Disposable {
        sinks += sink
        return Disposable { sinks -= sink }
    }

    fun dispatch(level: LogLevel, logger: String, message: String, throwable: Throwable?) {
        if (!isLoggable(level)) return
        val entry = LogEntry(
            timeMillis = System.currentTimeMillis(),
            level = level,
            logger = logger,
            message = message,
            stackTrace = throwable?.stackTraceToString()?.take(MAX_STACK_CHARS),
        )
        ring.add(entry)
        for (sink in sinks) {
            try {
                sink.write(entry, throwable)
            } catch (_: Throwable) {
                // A broken sink must never break the caller.
            }
        }
    }

    private companion object {
        const val MAX_STACK_CHARS = 8_000
    }
}

private class KernelLogger(override val name: String, private val hub: LogHub) : Logger {
    override fun log(level: LogLevel, message: String, throwable: Throwable?) =
        hub.dispatch(level, name, message, throwable)

    override fun isLoggable(level: LogLevel): Boolean = hub.isLoggable(level)

    override fun child(child: String): Logger = KernelLogger("$name/$child", hub)
}

private object AndroidLogSink : LogSink {
    override fun write(entry: LogEntry, throwable: Throwable?) {
        Log.println(entry.level.priority, LoaderIdentity.LOG_TAG, "[${entry.logger}] ${entry.message}")
        // Logcat truncates long lines, so the stack trace goes out as its own entry.
        entry.stackTrace?.let { Log.println(entry.level.priority, LoaderIdentity.LOG_TAG, it) }
    }
}
