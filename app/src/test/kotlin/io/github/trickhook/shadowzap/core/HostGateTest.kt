package io.github.trickhook.shadowzap.core

import android.content.pm.ApplicationInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HostGateTest {
    private val loader = HostGateTest::class.java.classLoader!!

    private fun appInfo(dataDir: String? = "/data/user/0/app") = ApplicationInfo().apply { this.dataDir = dataDir }

    /** A class loader that knows WhatsApp's marker class, like a repackaged WhatsApp build. */
    private val whatsAppLikeLoader = object : ClassLoader(loader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            if (name == HostGate.WHATSAPP_MARKER_CLASS) HostGateTest::class.java else super.loadClass(name, resolve)
    }

    private fun decide(
        packageName: String? = "com.whatsapp",
        processName: String? = packageName,
        info: ApplicationInfo? = appInfo(),
        classLoader: ClassLoader? = loader,
        first: Boolean = true,
    ) = HostGate.decide(packageName, processName, info, classLoader, first)

    @Test
    fun `boots in the main process of WhatsApp`() {
        assertEquals(HostGate.Decision.Boot, decide())
    }

    @Test
    fun `boots in a repackaged WhatsApp build`() {
        assertEquals(HostGate.Decision.Boot, decide(packageName = "com.example.zapmod", classLoader = whatsAppLikeLoader))
    }

    @Test
    fun `skips every other app`() {
        assertIs<HostGate.Decision.Skip>(decide(packageName = "com.example.notes"))
    }

    @Test
    fun `skips system_server and incomplete framework input`() {
        assertIs<HostGate.Decision.Skip>(decide(packageName = "android", info = null))
        assertIs<HostGate.Decision.Skip>(decide(packageName = null))
        assertIs<HostGate.Decision.Skip>(decide(info = null))
        assertIs<HostGate.Decision.Skip>(decide(info = appInfo(dataDir = null)))
        assertIs<HostGate.Decision.Skip>(decide(classLoader = null))
    }

    @Test
    fun `skips secondary processes and packages loaded into another process`() {
        assertIs<HostGate.Decision.Skip>(decide(processName = "com.whatsapp:voip"))
        assertIs<HostGate.Decision.Skip>(decide(processName = null))
        assertIs<HostGate.Decision.Skip>(decide(first = false))
    }
}
