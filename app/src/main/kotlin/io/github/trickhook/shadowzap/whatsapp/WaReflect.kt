package io.github.trickhook.shadowzap.whatsapp

import io.github.trickhook.shadowzap.core.FeatureContext
import java.lang.reflect.Method

/**
 * Small helpers for reaching into WhatsApp's R8-obfuscated classes without crashing a feature when a build changes.
 *
 * All lookups return `null` on miss and the caller logs; the feature stays installed, just inert.
 */
internal object WaReflect {
    /** Loads [dexName] via the host's class loader. Returns `null` if not found. */
    fun loadClass(ctx: FeatureContext, dexName: String, loader: ClassLoader = ctx.env.hostClassLoader): Class<*>? =
        try {
            Class.forName(dexName, false, loader)
        } catch (t: Throwable) {
            null
        }

    /** Returns the declared method matching [name] + [params], or `null`. */
    fun method(cls: Class<*>, name: String, vararg params: Class<*>): Method? = try {
        cls.getDeclaredMethod(name, *params)
    } catch (_: Throwable) {
        null
    }

    /** Returns every declared method of [cls] whose shape matches [predicate]. */
    fun methodsMatching(cls: Class<*>, predicate: (Method) -> Boolean): List<Method> =
        cls.declaredMethods.filter(predicate)

    /** Reads a static field or returns `null`. */
    fun staticField(cls: Class<*>, name: String): Any? = try {
        cls.getField(name).get(null)
    } catch (_: Throwable) {
        null
    }
}

/** Convenience: load a WhatsApp class with uniform logging on miss. */
internal fun FeatureContext.loadWaClass(dexName: String, loader: ClassLoader? = null): Class<*>? {
    val cl = loader ?: env.hostClassLoader
    return WaReflect.loadClass(this, dexName, cl) ?: run {
        log.w("$dexName not found on the host class loader; feature degrades gracefully.")
        null
    }
}
