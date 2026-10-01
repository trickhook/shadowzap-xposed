package io.github.trickhook.shadowzap.settings

import io.github.trickhook.shadowzap.core.Kernel

/**
 * Runtime probe of the WhatsApp client ↔ server surface. For each subsystem we know about, we check that the
 * canonical class name resolves on the host class loader and report whether it is present on this build. The result
 * drives the "Client ↔ Server" section of the About page.
 *
 * None of these probes load code — `Class.forName(name, false, loader)` is used so a hit does not run static
 * initialisers. Misses are silent.
 */
internal object WaProtocolInfo {
    data class Subsystem(val name: String, val className: String, val note: String)

    private val KNOWN: List<Subsystem> = listOf(
        Subsystem(
            "XMPP IQ engine",
            "X.C13160iC",
            "Core IQ send/receive, 32 s timeout per request.",
        ),
        Subsystem(
            "smax message client",
            "com.whatsapp.infra.xmpp.messaging.MessageClientSmaxWrapper",
            "Thin wrapper over the smax IQ-RPC codegen framework.",
        ),
        Subsystem(
            "Account RPC",
            "com.whatsapp.infra.smax.generated.account.outgoing.AccountRPCManager",
            "Per-domain outgoing IQ family for account stanzas.",
        ),
        Subsystem(
            "Spam RPC",
            "com.whatsapp.infra.smax.generated.spam.outgoing.SpamRPCManager",
            "Outgoing IQ family for spam/anti-abuse reporting.",
        ),
        Subsystem(
            "Syncd engine",
            "com.whatsapp.kmp.syncd.syncdengine.IncomingProcessor",
            "Multi-device app-state collection mutation protocol.",
        ),
        Subsystem(
            "Private AB experiments",
            "com.whatsapp.infra.privateexp.PrivateABExpFetcher",
            "Server-driven experiment assignment windows.",
        ),
        Subsystem(
            "Offline AB config",
            "com.whatsapp.fieldstats.offlineab.ConfigVariable",
            "Client-side flag registry (name, hashed id, default).",
        ),
        Subsystem(
            "Mex / Pando GraphQL",
            "com.whatsapp.pando.chatd.WAChatdGraphQLClient",
            "Meta GraphQL over Tigon/Mexd for non-XMPP features.",
        ),
        Subsystem(
            "TEE channel",
            "com.whatsapp.infra.tee.send.TeeRequestHandler",
            "Private-processing streaming boundary.",
        ),
        Subsystem(
            "OHAI / ACS",
            "com.whatsapp.infra.ohai.WaOhaiClient",
            "Anonymous-credential signalling to server.",
        ),
        Subsystem(
            "Wamo subscription",
            "com.whatsapp.wamo.request.WamoGraphQLExecutor",
            "Meta-Verified subscription GraphQL path.",
        ),
    )

    /** Result row for the About page. */
    data class Row(val name: String, val detected: Boolean, val className: String, val note: String)

    /** Resolves subsystem class names against the host class loader; order is preserved. */
    fun probe(): List<Row> {
        val loader = Kernel.current?.env?.hostClassLoader ?: WaProtocolInfo::class.java.classLoader
        return KNOWN.map { s ->
            val detected = try {
                Class.forName(s.className, false, loader)
                true
            } catch (_: Throwable) {
                false
            }
            Row(s.name, detected, s.className, s.note)
        }
    }

    /** Count how many of the probed subsystems are present. */
    fun summary(rows: List<Row> = probe()): String {
        val hit = rows.count { it.detected }
        return "$hit/${rows.size} subsystems detected"
    }
}
