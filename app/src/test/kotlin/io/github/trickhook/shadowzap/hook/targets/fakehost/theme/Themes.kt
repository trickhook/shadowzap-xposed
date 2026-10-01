package io.github.trickhook.shadowzap.hook.targets.fakehost.theme

// Stand-ins for a host theme package after DarkerTheme gave way to DarkTheme, AshTheme and OnyxTheme.

abstract class HostTheme {
    abstract fun primary(): Int
}

class AshTheme : HostTheme() {
    override fun primary(): Int = 1
}

class DarkTheme : HostTheme() {
    override fun primary(): Int = 2
}

class LightTheme : HostTheme() {
    override fun primary(): Int = 3
}

class OnyxTheme(val amoled: Boolean) : HostTheme() {
    constructor() : this(true)

    override fun primary(): Int = 4
}

object ThemeManager {
    @JvmField
    var current: HostTheme? = null

    @JvmField
    var fallback: HostTheme = LightTheme()

    fun apply(theme: HostTheme) {
        current = theme
    }
}
