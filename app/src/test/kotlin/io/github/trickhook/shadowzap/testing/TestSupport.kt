package io.github.trickhook.shadowzap.testing

import android.content.pm.ApplicationInfo
import io.github.trickhook.shadowzap.api.host.LogLevel
import io.github.trickhook.shadowzap.api.host.Logger
import io.github.trickhook.shadowzap.core.EntryKind
import io.github.trickhook.shadowzap.core.Env
import io.github.trickhook.shadowzap.core.Kernel
import io.github.trickhook.shadowzap.core.LogHub
import io.github.trickhook.shadowzap.hook.BackendHook
import io.github.trickhook.shadowzap.hook.HookBackend
import io.github.trickhook.shadowzap.hook.MemberHooks
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.nio.file.Files

/** Logger that records entries in memory. */
internal class RecordingLogger(override val name: String = "test") : Logger {
    val entries = mutableListOf<Triple<LogLevel, String, Throwable?>>()

    override fun log(level: LogLevel, message: String, throwable: Throwable?) {
        synchronized(entries) { entries += Triple(level, message, throwable) }
    }

    override fun child(child: String): Logger = this

    fun errors(): List<String> = synchronized(entries) { entries.filter { it.first == LogLevel.ERROR }.map { it.second } }
}

/**
 * In-memory [HookBackend]: installed members are called through [call] (around style, like libxposed) or
 * [callPhased] (before/after phases, like XposedBridge).
 */
internal class FakeHookBackend : HookBackend {
    override val name: String = "fake"

    val installed = LinkedHashMap<Member, MemberHooks>()
    var installCount = 0
    var refuseWith: Throwable? = null

    override fun install(member: Member, target: MemberHooks): BackendHook {
        refuseWith?.let { throw it }
        installCount++
        installed[member] = target
        return BackendHook { installed.remove(member) }
    }

    override fun invokeOriginal(member: Member, thisObject: Any?, args: Array<Any?>): Any? = try {
        (member as Method).apply { isAccessible = true }.invoke(thisObject, *args)
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    fun call(member: Member, thisObject: Any?, args: Array<Any?>, original: (Array<Any?>) -> Any?): Any? {
        val hooks = installed[member] ?: return original(args)
        return hooks.dispatch(thisObject, args, original)
    }

    /** Mirrors how XposedBridge drives before/after callbacks around the original. */
    fun callPhased(member: Member, thisObject: Any?, args: Array<Any?>, original: (Array<Any?>) -> Any?): Any? {
        val call = installed[member]?.beforePhase(thisObject, args) ?: return original(args)
        if (call.isOriginalSkipped) {
            call.finishAfter(call.result, call.throwable)
        } else {
            val outcome = runCatching { original(args) }
            call.finishAfter(outcome.getOrNull(), outcome.exceptionOrNull())
        }
        return call.resultOrThrow()
    }
}

internal object TestEnvs {
    fun tempDir(prefix: String = "shadowzap-test"): File = Files.createTempDirectory(prefix).toFile()

    fun env(dataDir: File, debug: Boolean = true): Env = Env(
        entry = EntryKind.MODERN,
        packageName = "com.whatsapp",
        processName = "com.whatsapp",
        appInfo = ApplicationInfo().apply {
            packageName = "com.whatsapp"
            this.dataDir = dataDir.absolutePath
        },
        hostClassLoader = TestEnvs::class.java.classLoader!!,
        modulePath = "",
        frameworkDescription = "test",
        isDebugBuild = debug,
        loaderVersion = "0.0.0-test",
        loaderVersionCode = 0,
    )

    fun kernel(dataDir: File = tempDir(), backend: HookBackend = FakeHookBackend()): Kernel =
        Kernel(env(dataDir), backend, LogHub(LogLevel.VERBOSE))
}
