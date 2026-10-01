package io.github.trickhook.shadowzap.hook

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * [HookBackend] on the libxposed modern API (101+): one interceptor per member that runs our dispatcher around
 * `chain.proceed`.
 *
 * Hooks use [XposedInterface.ExceptionMode.PASSTHROUGH]: the dispatcher already isolates every callback, so the only
 * throwables that leave the interceptor are the original's exception or one a callback deliberately set, and both
 * must reach the caller unchanged.
 */
internal class ModernHookBackend(private val xposed: XposedInterface) : HookBackend {
    override val name: String = "libxposed"

    override fun install(member: Member, target: MemberHooks): BackendHook {
        val executable = member as? Executable
            ?: throw IllegalArgumentException("Not a method or constructor: $member")
        val handle = xposed.hook(executable)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
            .intercept { chain ->
                val args = chain.args.toTypedArray<Any?>()
                target.dispatch(chain.thisObject, args) { finalArgs -> chain.proceed(finalArgs) }
            }
        return BackendHook { handle.unhook() }
    }

    override fun invokeOriginal(member: Member, thisObject: Any?, args: Array<Any?>): Any? = try {
        when (member) {
            is Method -> {
                val invoker = xposed.getInvoker(member).setType(XposedInterface.Invoker.Type.ORIGIN)
                (invoker as XposedInterface.Invoker<*, *>).invoke(thisObject, *args)
            }
            is Constructor<*> -> {
                val invoker = xposed.getInvoker(member).setType(XposedInterface.Invoker.Type.ORIGIN)
                if (thisObject == null) invoker.newInstance(*args) else invoker.invoke(thisObject, *args)
            }
            else -> throw IllegalArgumentException("Not a method or constructor: $member")
        }
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
}
