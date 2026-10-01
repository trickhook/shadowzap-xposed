package io.github.trickhook.shadowzap.hook.targets

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** What a [TargetSpec] resolves to: classes themselves, or methods, constructors or fields declared by them. */
internal enum class TargetKind { CLASS, METHOD, CONSTRUCTOR, FIELD }

/**
 * Describes one hook target of the host (WhatsApp) so [HostTargets] can find it even after the host
 * renamed or moved it. Built with the [Targets] DSL; immutable and safe to keep in a `val`.
 *
 * A spec names exact candidates (the classes the target is known to live in) and optionally how to recognise the
 * class by structure ([discovery]). For member kinds, the member constraints (name, parameter types, return type,
 * `concreteOnly`, field type) apply to exact and discovered classes alike; the predicates inside `discover { }` only
 * filter discovery candidates.
 */
internal class TargetSpec<T : Any> internal constructor(
    /** Unique id, e.g. `script.fromFile`: the memo, cache and report key. */
    val id: String,
    val kind: TargetKind,
    /**
     * Exact candidates, tried in order: the classes themselves for [TargetKind.CLASS], the classes searched for the
     * member otherwise. Binary names (`a.b.Outer$Inner`).
     */
    val exactClassNames: List<String>,
    /** How to find the class by structure when no exact candidate matches, or `null` for exact-only specs. */
    val discovery: TargetDiscovery<T>?,
    /** `true`: every match is kept (all candidates, all matching members). `false`: only the first match. */
    val multiple: Boolean,
    internal val query: MemberQuery<T>,
) {
    /**
     * The values of this spec in [cls]: [cls] itself for [TargetKind.CLASS], otherwise the members declared by [cls]
     * (not inherited ones; never bridge methods) that satisfy the member constraints, in a stable order. Members
     * whose signature types cannot be resolved are skipped.
     */
    fun candidatesIn(cls: Class<*>): List<T> = query.candidatesIn(cls)

    /** Human-readable name of one value, e.g. `a.B#load(String, boolean)`. */
    fun describe(value: T): String = query.describe(value)

    /** Human-readable member constraints, e.g. `#load(String, boolean): int`; empty for [TargetKind.CLASS]. */
    val constraints: String get() = query.constraints

    override fun toString(): String = "TargetSpec($id, $kind)"
}

/**
 * How discovery recognises the class of a [TargetSpec] (and, for member kinds, the member) by structure.
 *
 * Class names are filtered with [acceptsName] before any class is loaded, so discovery only ever loads classes of
 * the listed [packages] whose names pass [nameFilter].
 */
internal class TargetDiscovery<T : Any> internal constructor(
    /** Package prefixes; a class matches its package or any subpackage. Empty: any package ([nameFilter] is set). */
    val packages: List<String>,
    /** Tested with [Regex.containsMatchIn] against the binary class name (`a.b.Outer$Inner`); `null`: any name. */
    val nameFilter: Regex?,
    /** Structural test of a loaded candidate class; `null`: every class that passes the name filter. */
    val classPredicate: ((Class<*>) -> Boolean)?,
    /** Extra test of each member that satisfies the member constraints; `null` for classes or when not given. */
    val memberPredicate: ((T) -> Boolean)?,
) {
    /** Cheap prefilter on a binary class name, applied before the class is loaded. */
    fun acceptsName(className: String): Boolean {
        if (packages.isNotEmpty() && packages.none { isInPackage(className, it) }) return false
        return nameFilter?.containsMatchIn(className) ?: true
    }

    /** Whether a loaded candidate class passes the class predicate. */
    fun acceptsClass(cls: Class<*>): Boolean = classPredicate?.invoke(cls) ?: true

    /** Whether a member (or, for [TargetKind.CLASS], the class) passes the member predicate. */
    fun acceptsMember(value: T): Boolean = memberPredicate?.invoke(value) ?: true

    private fun isInPackage(className: String, pkg: String): Boolean =
        className.length > pkg.length && className.startsWith(pkg) && className[pkg.length] == '.'
}

/** The member part of a [TargetSpec]: which values a class contributes and how they are named. */
internal sealed class MemberQuery<T : Any> {
    abstract fun candidatesIn(cls: Class<*>): List<T>

    abstract fun describe(value: T): String

    abstract val constraints: String

    object Classes : MemberQuery<Class<*>>() {
        override fun candidatesIn(cls: Class<*>): List<Class<*>> = listOf(cls)

        override fun describe(value: Class<*>): String = value.name

        override val constraints: String get() = ""
    }

    class Methods(
        /** `null`: any name. */
        val name: String?,
        /** `null`: any parameters. */
        val params: List<Class<*>>?,
        /** `null`: any return type. */
        val returns: Class<*>?,
        val concreteOnly: Boolean,
    ) : MemberQuery<Method>() {
        override fun candidatesIn(cls: Class<*>): List<Method> {
            val declared = try {
                cls.declaredMethods.asList()
            } catch (e: LinkageError) {
                // Some signature in the class does not resolve; look the method up directly when it is fully named.
                if (name == null || params == null) throw e
                listOfNotNull(
                    try {
                        cls.getDeclaredMethod(name, *params.toTypedArray())
                    } catch (_: NoSuchMethodException) {
                        null
                    },
                )
            }
            return declared.filter(::matches).sortedBy { sortKey(it) }
        }

        private fun matches(method: Method): Boolean = unlessUnresolvable {
            !method.isBridge &&
                (name == null || method.name == name) &&
                (params == null || method.parameterTypes.asList() == params) &&
                (returns == null || method.returnType == returns) &&
                (!concreteOnly || !Modifier.isAbstract(method.modifiers))
        } ?: false

        override fun describe(value: Method): String =
            "${value.declaringClass.name}#${value.name}(${paramList(value::getParameterTypes)})"

        override val constraints: String
            get() = buildString {
                append('#').append(name ?: "*")
                append('(').append(params?.let(::typeList) ?: "..").append(')')
                returns?.let { append(": ").append(typeName(it)) }
                if (concreteOnly) append(" [concrete]")
            }
    }

    class Constructors(
        /** `null`: any parameters. */
        val params: List<Class<*>>?,
    ) : MemberQuery<Constructor<*>>() {
        override fun candidatesIn(cls: Class<*>): List<Constructor<*>> {
            val declared = try {
                cls.declaredConstructors.asList()
            } catch (e: LinkageError) {
                if (params == null) throw e
                listOfNotNull(
                    try {
                        cls.getDeclaredConstructor(*params.toTypedArray())
                    } catch (_: NoSuchMethodException) {
                        null
                    },
                )
            }
            return declared.filter(::matches).sortedBy { sortKey(it) }
        }

        private fun matches(constructor: Constructor<*>): Boolean = unlessUnresolvable {
            params == null || constructor.parameterTypes.asList() == params
        } ?: false

        override fun describe(value: Constructor<*>): String =
            "${value.declaringClass.name}#<init>(${paramList(value::getParameterTypes)})"

        override val constraints: String
            get() = "#<init>(" + (params?.let(::typeList) ?: "..") + ")"
    }

    class Fields(
        /** `null`: any name. */
        val name: String?,
        /** `null`: any type. */
        val type: Class<*>?,
    ) : MemberQuery<Field>() {
        override fun candidatesIn(cls: Class<*>): List<Field> {
            val declared = try {
                cls.declaredFields.asList()
            } catch (e: LinkageError) {
                if (name == null) throw e
                listOfNotNull(
                    try {
                        cls.getDeclaredField(name)
                    } catch (_: NoSuchFieldException) {
                        null
                    },
                )
            }
            return declared.filter(::matches).sortedBy { it.name }
        }

        private fun matches(field: Field): Boolean = unlessUnresolvable {
            (name == null || field.name == name) && (type == null || field.type == type)
        } ?: false

        override fun describe(value: Field): String = "${value.declaringClass.name}#${value.name}"

        override val constraints: String
            get() = "#" + (name ?: "*") + (type?.let { ": " + typeName(it) } ?: "")
    }
}

private fun typeName(type: Class<*>): String = when {
    type.isArray -> typeName(type.componentType!!) + "[]"
    type.isPrimitive || type.name.startsWith("java.lang.") -> type.simpleName
    else -> type.name
}

private fun typeList(types: List<Class<*>>): String = types.joinToString(", ", transform = ::typeName)

/** Runs [block], or returns `null` when a type in a member signature cannot be resolved. */
private inline fun <R> unlessUnresolvable(block: () -> R): R? = try {
    block()
} catch (_: LinkageError) {
    null
} catch (_: TypeNotPresentException) {
    null
}

private fun paramList(types: () -> Array<Class<*>>): String = unlessUnresolvable { typeList(types().asList()) } ?: "?"

private fun sortKey(method: Method): String = method.name + "(" +
    (unlessUnresolvable { method.parameterTypes.joinToString(",") { it.name } + ")" + method.returnType.name } ?: "?)")

private fun sortKey(constructor: Constructor<*>): String =
    "(" + (unlessUnresolvable { constructor.parameterTypes.joinToString(",") { it.name } } ?: "?") + ")"
