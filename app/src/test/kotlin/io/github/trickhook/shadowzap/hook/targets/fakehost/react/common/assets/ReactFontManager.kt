package io.github.trickhook.shadowzap.hook.targets.fakehost.react.common.assets

// Stand-in for React Native's ReactFontManager after it moved from views.text to common.assets.

class ReactFontManager {
    fun addCustomFont(fontFamily: String, fontPath: String) = Unit

    companion object {
        @JvmStatic
        val instance: ReactFontManager = ReactFontManager()
    }
}

class ReactTypefaceUtils
