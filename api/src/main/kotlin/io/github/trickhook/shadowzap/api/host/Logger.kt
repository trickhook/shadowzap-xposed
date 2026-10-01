package io.github.trickhook.shadowzap.api.host

/** Severity of a log entry, mirroring `android.util.Log` priorities. */
public enum class LogLevel(public val priority: Int) {
    VERBOSE(2),
    DEBUG(3),
    INFO(4),
    WARN(5),
    ERROR(6),
}

/**
 * Namespaced logger provided by the host.
 *
 * Entries go to logcat under the `Shadowzap` tag, prefixed with [name], and are kept in an in-memory ring buffer
 * that the recovery UI can show.
 */
public interface Logger {
    /** The namespace printed with every entry, for example `feature:media.status`. */
    public val name: String

    /** Writes one entry. [throwable], when present, is logged with its stack trace. */
    public fun log(level: LogLevel, message: String, throwable: Throwable? = null)

    /** Whether entries at [level] are currently recorded; use it to skip building expensive messages. */
    public fun isLoggable(level: LogLevel): Boolean = true

    /** Returns a logger whose namespace is `"$name/$child"`. */
    public fun child(child: String): Logger

    public fun v(message: String, throwable: Throwable? = null): Unit = log(LogLevel.VERBOSE, message, throwable)
    public fun d(message: String, throwable: Throwable? = null): Unit = log(LogLevel.DEBUG, message, throwable)
    public fun i(message: String, throwable: Throwable? = null): Unit = log(LogLevel.INFO, message, throwable)
    public fun w(message: String, throwable: Throwable? = null): Unit = log(LogLevel.WARN, message, throwable)
    public fun e(message: String, throwable: Throwable? = null): Unit = log(LogLevel.ERROR, message, throwable)
}

/** Logs at [LogLevel.DEBUG], building the message only when debug logging is enabled. */
public inline fun Logger.debug(throwable: Throwable? = null, message: () -> String) {
    if (isLoggable(LogLevel.DEBUG)) log(LogLevel.DEBUG, message(), throwable)
}
