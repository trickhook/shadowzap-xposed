package io.github.trickhook.shadowzap.settings

/** What the Shadowzap settings page shows: sections of switches, in order. Pure data, so it can be tested. */
internal object SettingsModel {
    class Item(val id: String, val title: String, val description: String)

    class Section(val title: String, val items: List<Item>)

    val sections: List<Section> = listOf(
        Section(
            "Mídia",
            listOf(
                Item(Switches.STATUS_DOWNLOAD, "Baixar status", "Mostra um botão para salvar fotos e vídeos dos status na galeria."),
                Item(Switches.HD_IMAGES, "Fotos em HD", "Envia fotos na qualidade original, sem a compressão do WhatsApp."),
                Item(Switches.HD_VIDEOS, "Vídeos em HD", "Envia vídeos na qualidade original, sem a compressão do WhatsApp."),
            ),
        ),
    )

    /** Ids of every switch on the page. */
    val ids: List<String> get() = sections.flatMap { section -> section.items.map { it.id } }
}
