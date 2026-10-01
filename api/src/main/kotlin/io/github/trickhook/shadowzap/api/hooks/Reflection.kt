@file:JvmName("Reflection")

package io.github.trickhook.shadowzap.api.hooks

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Thrown when a class, method, constructor or field needed for a hook cannot be found. */
public class HookTargetNotFoundException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Loads [name] with this class loader, or returns `null` when it does not exist. */
public fun ClassLoader.classOrNull(name: String): Class<*>? = try {
    loadClass(name)
} catch (_: ClassNotFoundException) {
    null
} catch (_: LinkageError) {
    null
}

/** Loads [name] with this class loader or throws [HookTargetNotFoundException]. */
public fun ClassLoader.requireClass(name: String): Class<*> = try {
    loadClass(name)
} catch (e: ClassNotFoundException) {
    throw HookTargetNotFoundException("Class $name not found", e)
} catch (e: LinkageError) {
    throw HookTargetNotFoundException("Class $name failed to link", e)
}

/** Returns the first class in [names] that exists, or throws listing every name that was tried. */
public fun ClassLoader.requireAnyClass(vararg names: String): Class<*> =
    names.firstNotNullOfOrNull { classOrNull(it) }
        ?: throw HookTargetNotFoundException("None of these classes exist: ${names.joinToString()}")

/**
 * Resolves type names to classes: primitives (`int`, `boolean`, ...), arrays (`java.lang.String[]`) and class
 * names loaded through this class loader.
 */
public fun ClassLoader.resolveTypes(vararg typeNames: String): Array<Class<*>> =
    Array(typeNames.size) { resolveType(typeNames[it]) }

private val PRIMITIVES: Map<String, Class<*>> = mapOf(
    "boolean" to java.lang.Boolean.TYPE,
    "byte" to java.lang.Byte.TYPE,
    "char" to Character.TYPE,
    "short" to java.lang.Short.TYPE,
    "int" to Integer.TYPE,
    "long" to java.lang.Long.TYPE,
    "float" to java.lang.Float.TYPE,
    "double" to java.lang.Double.TYPE,
    "void" to Void.TYPE,
)

private fun ClassLoader.resolveType(name: String): Class<*> {
    val trimmed = name.trim()
    if (trimmed.endsWith("[]")) {
        val component = resolveType(trimmed.removeSuffix("[]"))
        return java.lang.reflect.Array.newInstance(component, 0).javaClass
    }
    return PRIMITIVES[trimmed] ?: requireClass(trimmed)
}

/**
 * Finds a method declared by this class or one of its superclasses with exactly [paramTypes], makes it accessible
 * and returns it.
 *
 * @throws HookTargetNotFoundException listing the overloads that do exist when there is no match.
 */
public fun Class<*>.requireMethod(name: String, vararg paramTypes: Class<*>): Method =
    methodOrNull(name, *paramTypes)
        ?: throw HookTargetNotFoundException(
            "Method ${this.name}#$name(${paramTypes.joinToString { it.prettyName() }}) not found. " +
                describeCandidates(methodsNamed(name)),
        )

/** Like [requireMethod] but returns `null` when there is no match. */
public fun Class<*>.methodOrNull(name: String, vararg paramTypes: Class<*>): Method? {
    var type: Class<*>? = this
    while (type != null) {
        val found = try {
            type.getDeclaredMethod(name, *paramTypes)
        } catch (_: NoSuchMethodException) {
            null
        }
        if (found != null) return found.apply { isAccessible = true }
        type = type.superclass
    }
    return null
}

/** All methods named [name] declared by this class or its superclasses, subclass first, made accessible. */
public fun Class<*>.methodsNamed(name: String): List<Method> {
    val result = ArrayList<Method>()
    var type: Class<*>? = this
    while (type != null) {
        type.declaredMethods.filterTo(result) { it.name == name }
        type = type.superclass
    }
    result.forEach { it.isAccessible = true }
    return result
}

/**
 * Finds the only method named [name] declared directly by this class, for members whose parameter types change
 * between host versions.
 *
 * @throws HookTargetNotFoundException when there is no such method or when it is overloaded.
 */
public fun Class<*>.requireMethodByName(name: String): Method {
    val candidates = declaredMethods.filter { it.name == name && !it.isSynthetic && !it.isBridge }
    return when (candidates.size) {
        1 -> candidates.single().apply { isAccessible = true }
        0 -> throw HookTargetNotFoundException("Method ${this.name}#$name not found")
        else -> throw HookTargetNotFoundException(
            "Method ${this.name}#$name is ambiguous. " + describeCandidates(candidates),
        )
    }
}

/** Finds the constructor with exactly [paramTypes], makes it accessible and returns it. */
public fun <T> Class<T>.requireConstructor(vararg paramTypes: Class<*>): Constructor<T> =
    constructorOrNull(*paramTypes)
        ?: throw HookTargetNotFoundException(
            "Constructor ${this.name}(${paramTypes.joinToString { it.prettyName() }}) not found. " +
                describeCandidates(declaredConstructors.toList()),
        )

/** Like [requireConstructor] but returns `null` when there is no match. */
public fun <T> Class<T>.constructorOrNull(vararg paramTypes: Class<*>): Constructor<T>? = try {
    getDeclaredConstructor(*paramTypes).apply { isAccessible = true }
} catch (_: NoSuchMethodException) {
    null
}

/** Finds a field declared by this class or a superclass, makes it accessible and returns it. */
public fun Class<*>.requireField(name: String): Field =
    fieldOrNull(name) ?: throw HookTargetNotFoundException("Field ${this.name}.$name not found")

/** Like [requireField] but returns `null` when there is no such field. */
public fun Class<*>.fieldOrNull(name: String): Field? {
    var type: Class<*>? = this
    while (type != null) {
        val found = try {
            type.getDeclaredField(name)
        } catch (_: NoSuchFieldException) {
            null
        }
        if (found != null) return found.apply { isAccessible = true }
        type = type.superclass
    }
    return null
}

/** Reads the static field [name]. */
public fun Class<*>.readStaticField(name: String): Any? {
    val field = requireField(name)
    require(Modifier.isStatic(field.modifiers)) { "Field ${this.name}.$name is not static" }
    return field.get(null)
}

private fun describeCandidates(candidates: Collection<Any>): String =
    if (candidates.isEmpty()) {
        "No candidates exist."
    } else {
        "Candidates: " + candidates.joinToString("; ") { candidate ->
            when (candidate) {
                is Method -> "${candidate.name}(${candidate.parameterTypes.joinToString { it.prettyName() }})"
                is Constructor<*> -> "<init>(${candidate.parameterTypes.joinToString { it.prettyName() }})"
                else -> candidate.toString()
            }
        }
    }

private fun Class<*>.prettyName(): String = if (isArray) componentType!!.prettyName() + "[]" else name