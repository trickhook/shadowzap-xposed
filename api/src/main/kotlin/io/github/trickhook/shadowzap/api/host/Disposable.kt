package io.github.trickhook.shadowzap.api.host

/**
 * Something that can be released: a hook, a lifecycle subscription, a listener.
 *
 * [dispose] is idempotent; calling it more than once has no further effect.
 */
public fun interface Disposable {
    /** Releases the resource. Safe to call repeatedly and from any thread. */
    public fun dispose()

    public companion object {
        /** A [Disposable] that does nothing, for subscriptions that completed immediately. */
        public val NONE: Disposable = Disposable { }
    }
}
