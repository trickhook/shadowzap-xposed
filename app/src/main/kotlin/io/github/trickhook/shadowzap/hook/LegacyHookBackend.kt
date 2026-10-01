package io.github.trickhook.shadowzap.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member

/**
 * [HookBackend] on the legacy XposedBridge API (legacy frameworks and libxposed API 100 frameworks). Our dispatcher
 * maps onto Xposed's before/after phases, carrying the call state between them in the param's object extras, so
 * other modules' hooks on the same member keep working.
 */
internal class LegacyHookBackend : HookBackend {
    override val name: String = "XposedBridge"

    override fun install(member: Member, target: MemberHooks): BackendHook {
        // LSPosed's bridge logs and returns null when the native hook fails instead of throwing.
        val unhook: XC_MethodHook.Unhook = XposedBridge.hookMethod(member, DispatchingHook(target))
            ?: throw IllegalStateException("XposedBridge refused to hook $member")
        return BackendHook { unhook.unhook() }
    }

    override fun invokeOriginal(member: Member, thisObject: Any?, args: Array<Any?>): Any? = try {
        if (member is Constructor<*> && thisObject == null) {
            // XposedBridge runs a constructor on an existing receiver; allocate one, then initialise it.
            val instance = UnsafeAllocator.allocate(member.declaringClass)
            XposedBridge.invokeOriginalMethod(member, instance, args)
            instance
        } else {
            XposedBridge.invokeOriginalMethod(member, thisObject, args)
        }
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }

    private class DispatchingHook(private val target: MemberHooks) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            // param.args is the array the original receives, so argument edits apply directly.
            val call = target.beforePhase(param.thisObject, param.args) ?: return
            param.setObjectExtra(CALL_KEY, call)
            if (call.isOriginalSkipped) apply(param, call)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val call = param.getObjectExtra(CALL_KEY) as? ActiveCall ?: return
            call.finishAfter(param.result, param.throwable)
            apply(param, call)
        }

        private fun apply(param: MethodHookParam, call: ActiveCall) {
            val throwable = call.throwable
            if (throwable != null) param.throwable = throwable else param.result = call.result
        }
    }

    private companion object {
        const val CALL_KEY = "io.github.trickhook.shadowzap.call"
    }
}

/** Allocates an object without running any constructor, so the original constructor can initialise it. */
internal object UnsafeAllocator {
    private val allocateInstance: (Class<*>) -> Any by lazy {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.declaredFields.firstOrNull { it.type == unsafeClass }
            ?: throw UnsupportedOperationException("sun.misc.Unsafe has no singleton field")
        field.isAccessible = true
        val unsafe = field.get(null)
        val method = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val allocator: (Class<*>) -> Any = { type -> method.invoke(unsafe, type)!! }
        allocator
    }

    fun allocate(type: Class<*>): Any = try {
        allocateInstance(type)
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    } catch (t: Throwable) {
        throw UnsupportedOperationException("Cannot create an instance of ${type.name} without its constructor", t)
    }
}
