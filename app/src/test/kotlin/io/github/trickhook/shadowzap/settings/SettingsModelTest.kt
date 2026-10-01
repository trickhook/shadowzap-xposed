package io.github.trickhook.shadowzap.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsModelTest {
    @Test
    fun `the page shows every switch exactly once`() {
        val ids = SettingsModel.ids
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: $ids")
        assertEquals(Switches.DEFAULTS.keys, ids.toSet())
    }

    @Test
    fun `every item has a title and a description`() {
        for (section in SettingsModel.sections) {
            assertTrue(section.title.isNotBlank())
            assertTrue(section.items.isNotEmpty(), section.title)
            for (item in section.items) {
                assertTrue(item.title.isNotBlank(), item.id)
                assertTrue(item.description.isNotBlank(), item.id)
            }
        }
    }
}
