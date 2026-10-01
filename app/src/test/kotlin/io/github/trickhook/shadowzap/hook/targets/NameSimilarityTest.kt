package io.github.trickhook.shadowzap.hook.targets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NameSimilarityTest {
    @Test
    fun `edit distance`() {
        assertEquals(0, NameSimilarity.levenshtein("DarkTheme", "DarkTheme"))
        assertEquals(2, NameSimilarity.levenshtein("DarkerTheme", "DarkTheme"))
        assertEquals(3, NameSimilarity.levenshtein("kitten", "sitting"))
        assertEquals(4, NameSimilarity.levenshtein("", "abcd"))
        assertEquals(1.0, NameSimilarity.ratio("", ""))
    }

    @Test
    fun `the renamed theme is closer than its siblings`() {
        val reference = "org.sample.host.theme.DarkerTheme"
        val candidates = listOf("AshTheme", "DarkTheme", "DarkThemeExperiment", "LightTheme", "OnyxTheme")
            .map { "org.sample.host.theme.$it" }
        assertEquals("org.sample.host.theme.DarkTheme", candidates.maxBy { NameSimilarity.score(it, listOf(reference)) })
    }

    @Test
    fun `a moved class beats a neighbour of the old package`() {
        val reference = "com.facebook.react.views.text.ReactFontManager"
        val moved = NameSimilarity.score("com.facebook.react.common.assets.ReactFontManager", reference)
        val neighbour = NameSimilarity.score("com.facebook.react.views.text.ReactTypefaceUtils", reference)
        assertTrue(moved > neighbour, "$moved vs $neighbour")
    }

    @Test
    fun `the closest of several references counts, none scores zero`() {
        val references = listOf("a.b.Gone", "org.sample.host.theme.DarkerTheme")
        assertEquals(
            NameSimilarity.score("org.sample.host.theme.DarkTheme", "org.sample.host.theme.DarkerTheme"),
            NameSimilarity.score("org.sample.host.theme.DarkTheme", references),
        )
        assertEquals(0.0, NameSimilarity.score("a.B", emptyList()))
        assertEquals(1.0, NameSimilarity.score("a.b.C", "a.b.C"))
    }
}
