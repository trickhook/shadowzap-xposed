package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.whatsapp.AnonymousStatusViewFeature
import io.github.trickhook.shadowzap.whatsapp.AntiRevokeFeature
import io.github.trickhook.shadowzap.whatsapp.DebugEnableFeature
import io.github.trickhook.shadowzap.whatsapp.FlagSecureStripFeature
import io.github.trickhook.shadowzap.whatsapp.GhostModeFeature
import io.github.trickhook.shadowzap.whatsapp.HdVideosFeature
import io.github.trickhook.shadowzap.whatsapp.MediaAutoPreviewFeature
import io.github.trickhook.shadowzap.whatsapp.NoStatusTrimFeature
import io.github.trickhook.shadowzap.whatsapp.PropsUnlockFeature
import io.github.trickhook.shadowzap.whatsapp.SeeEditedFeature
import io.github.trickhook.shadowzap.whatsapp.SettingsEntryFeature
import io.github.trickhook.shadowzap.whatsapp.StatusDownloadFeature
import io.github.trickhook.shadowzap.whatsapp.UnlimitedPinsFeature
import io.github.trickhook.shadowzap.whatsapp.ViewOnceBypassFeature
import io.github.trickhook.shadowzap.whatsapp.ViewOnceSaveFeature
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

        // UI: add our entry inside WhatsApp's own Settings.
        SettingsEntryFeature,

        // Media: a save button on the Status viewer.
        StatusDownloadFeature,

        // Media: remove the 30 s cap when posting a video to Status.
        NoStatusTrimFeature,

        // Media: raise ProcessVideoQuality caps so sent videos keep FullHD + up to 120 fps.
        HdVideosFeature,

        // Privacy / networking: install early so the first socket resume doesn't leak receipts or presence.
        GhostModeFeature,

        // Privacy: strip FLAG_SECURE so screenshots work on view-once / chat lock / 2FA / payments.
        FlagSecureStripFeature,

        // Privacy / Status: suppress the "played" receipt on status broadcasts.
        AnonymousStatusViewFeature,

        // Chat: lift the 3-pin cap on the chat list.
        UnlimitedPinsFeature,

        // Privacy: keep revoked messages visible. Must beat the DAO on the first revoke stanza.
        AntiRevokeFeature,

        // Privacy: hide the view-once viewer. Runs during FMessage inflation.
        ViewOnceBypassFeature,

        // Privacy / Media: adds a Save entry to the 3-dot menu of WA's view-once viewer.
        ViewOnceSaveFeature,

        // Privacy: ignore incoming edits.
        SeeEditedFeature,

        // Media: autodownload. Runs after the message is persisted, so it goes last.
        MediaAutoPreviewFeature,

        // Developer: let JDWP / frida attach without WA swallowing input.
        DebugEnableFeature,

        // Developer: force a curated allowlist of A/B boolean props to true. Install late — must hook C00D after it
        // is on the class loader, and after other features that might trigger prop reads during their own install.
        PropsUnlockFeature,
    )
}
