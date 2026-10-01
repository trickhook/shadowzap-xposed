package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.whatsapp.WhatsAppInfoFeature

/**
 * The fixed install order of every feature. Owned by the kernel: implementation areas fill in their feature objects
 * but never edit this list. docs/DESIGN.md explains why each position matters.
 */
internal object Features {
    fun ordered(): List<Feature> = listOf(
        // Kernel: must exist before anything subscribes to the context or the activities.
        LifecycleFeature,

        // Host facts first, so everything after can read HostInfo.
        WhatsAppInfoFeature,
    )
}
