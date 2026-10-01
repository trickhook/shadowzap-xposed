package io.github.trickhook.shadowzap.hook

import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Checks hook results against the hooked method's return type before they reach the framework. A `null` for a
 * primitive makes the framework throw into the host's caller, and a wrong-typed object is handed back unchecked
 * (heap type confusion that crashes later), so an invalid result must never leave the dispatcher.
 */
internal object ReturnTypes {
    /** Why [result] cannot be returned from [member], or `null` when it can. Constructors and `void` accept anything. */
    fun problem(member: Member, result: Any?): String? {
        val method = member as? Method ?: return null
        val type = method.returnType
        if (type == Void.TYPE) return null
        if (result == null) return if (type.isPrimitive) "null for primitive return type ${type.name}" else null
        // For a primitive this is its wrapper (int -> java.lang.Integer), which is what frameworks unbox.
        val expected = type.kotlin.javaObjectType
        return if (expected.isInstance(result)) null else "${result.javaClass.name} is not a ${type.name}"
    }
}
