package io.github.trickhook.shadowzap.settings

/** What the Shadowzap settings page shows: sections of switches, in order. Pure data, so it can be tested. */
internal object SettingsModel {
    class Item(val id: String, val title: String, val description: String)

    class Section(val title: String, val items: List<Item>)

    val sections: List<Section> = listOf(
        Section(
            "Media",
            listOf(
                Item(Switches.STATUS_DOWNLOAD, "Save statuses", "Show a button to save photos and videos from Status to your gallery."),
                Item(Switches.HD_IMAGES, "Send photos in HD", "Keep photos at full quality, skipping WhatsApp's compression."),
                Item(Switches.HD_VIDEOS, "Send videos in HD", "Keep videos at full quality, skipping WhatsApp's transcoding."),
                Item(Switches.NO_STATUS_TRIM, "Unlimited status videos", "Lifts WhatsApp's 30-second cap when posting a video to Status (up to 1 hour)."),
                Item(Switches.MEDIA_AUTOPREVIEW, "Auto-preview media", "Fetch images and videos automatically so they appear in chat without the download arrow."),
            ),
        ),
        Section(
            "Privacy",
            listOf(
                Item(Switches.ANTI_REVOKE, "Anti-revoke", "Keep messages the sender tried to delete for everyone."),
                Item(Switches.SEE_EDITED, "See edited messages", "Keep the original text of edited messages."),
                Item(Switches.VIEW_ONCE_BYPASS, "Bypass view once", "Reopen, save and forward view-once media as normal messages."),
                Item(
                    Switches.VIEW_ONCE_SAVE,
                    "Save view-once photos",
                    "Add a Save entry to the 3-dot menu inside WhatsApp's view-once viewer.",
                ),
                Item(
                    Switches.GHOST_MODE,
                    "Ghost mode (1 tick)",
                    "Senders stay at 1 tick (sent, not delivered) even when you read or reply. Also hides online, typing and read receipts. Server-observable — use a burner account.",
                ),
                Item(
                    Switches.STRIP_FLAG_SECURE,
                    "Allow screenshots",
                    "Strip FLAG_SECURE so you can screenshot view-once media, chat lock, 2FA and payments.",
                ),
            ),
        ),
        Section(
            "Chat",
            listOf(
                Item(Switches.UNLIMITED_PINS, "Unlimited pinned chats", "Pin more than 3 chats on the home list."),
            ),
        ),
        Section(
            "Status",
            listOf(
                Item(
                    Switches.ANONYMOUS_STATUS,
                    "Anonymous status view",
                    "Watch friends' status without appearing in their viewer list.",
                ),
            ),
        ),
        Section(
            "Developer",
            listOf(
                Item(
                    Switches.ALLOW_DEBUG,
                    "Allow debugging",
                    "Let JDWP, frida and other debuggers attach without WhatsApp swallowing input. Native integrity checks (libwasafe) are not covered — use a burner account.",
                ),
                Item(
                    Switches.PROPS_UNLOCK,
                    "Unlock A/B props",
                    "Force a curated allowlist of WhatsApp A/B boolean props to true (hidden features). Server-controlled, use a burner account.",
                ),
            ),
        ),
    )

    /** Ids of every switch on the page. */
    val ids: List<String> get() = sections.flatMap { section -> section.items.map { it.id } }
}
