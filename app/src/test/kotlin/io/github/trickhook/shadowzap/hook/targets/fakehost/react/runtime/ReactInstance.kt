package io.github.trickhook.shadowzap.hook.targets.fakehost.react.runtime

// Stand-ins for React Native's bridgeless script loading, with the delegate class renamed.

interface BundleLoaderDelegate {
    fun loadScriptFromFile(fileName: String, sourceUrl: String, loadSynchronously: Boolean)

    fun loadScriptFromAssets(assetUrl: String, loadSynchronously: Boolean)
}

class ReactInstance {
    val loaded = mutableListOf<String>()

    /** Was `ReactInstance$loadJSBundle$1` before the rename. */
    inner class BundleDelegate : BundleLoaderDelegate {
        override fun loadScriptFromFile(fileName: String, sourceUrl: String, loadSynchronously: Boolean) {
            loaded += fileName
        }

        override fun loadScriptFromAssets(assetUrl: String, loadSynchronously: Boolean) {
            loaded += assetUrl
        }
    }
}

/** Same method name, other signature, not a delegate: must never be picked. */
class ScriptDecoy {
    fun loadScriptFromFile(fileName: String, sourceUrl: String) = Unit
}
