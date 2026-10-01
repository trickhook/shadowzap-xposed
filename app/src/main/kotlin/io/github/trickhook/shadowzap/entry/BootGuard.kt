package io.github.trickhook.shadowzap.entry

/**
 * Makes sure only one entry point boots the loader per process.
 *
 * A framework may load both the modern and the legacy entry, possibly through different class loaders, so the
 * flag lives in a JVM system property: those are process-wide and shared by every class loader.
 */
internal object BootGuard {
    const val PROPERTY: String = "shadowzap.xposed.booted"

    /** Claims the boot for [entry]. Returns `false` when another entry (or an earlier call) already booted. */
    fun claim(entry: String): Boolean {
        val properties = System.getProperties()
        synchronized(properties) {
            if (properties.getProperty(PROPERTY) != null) return false
            properties.setProperty(PROPERTY, entry)
            return true
        }
    }

    /** The entry that booted, or `null`. */
    fun claimedBy(): String? = System.getProperty(PROPERTY)
}
