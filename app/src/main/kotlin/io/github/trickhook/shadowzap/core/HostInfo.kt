package io.github.trickhook.shadowzap.core

import io.github.trickhook.shadowzap.api.version.Version

/**
 * Facts about the host app discovered at runtime. Written by the whatsapp/ area once the host Application exists,
 * read by everyone. All fields stay `null` when they could not be determined; readers must cope with that.
 */
internal class HostInfo {
    /** Host version name from the package manager, e.g. `2.26.39.11`. */
    @Volatile
    var versionName: String? = null

    /** [versionName] parsed leniently into a [Version], or `null` when it could not be parsed. */
    @Volatile
    var version: Version? = null

    /** Host version code from the package manager. */
    @Volatile
    var versionCode: Long? = null
}
