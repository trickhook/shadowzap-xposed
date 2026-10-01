package io.github.trickhook.shadowzap.hook.targets

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Keeps the builders of the [Targets] DSL apart: `exactIn` cannot be called from inside `discover { }`. */
@DslMarker
internal annotation class TargetsDsl

/**
 * Builds [TargetSpec]s. Specs are pure descriptions: nothing is loaded until [HostTargets.resolve].
 *
 * ```
 * val SETTINGS_CREATE = Targets.method("settings.onCreate") {
 *     exactIn("com.whatsapp.settings.Settings")
 *     name = "onCreate"
 *     params(Bundle::class.java)
 *     concreteOnly = true
 *     discover {
 *         packages("com.whatsapp.settings")
 *         where { cls -> Activity::class.java.isAssignableFrom(cls) }
 *     }
 * }
 * ```
 *
 * Mistakes in a spec (blank id, no candidates and no discovery, a discovery without `packages` or `nameFilter`,
 * parameters neither given nor declared free, a setting given twice) throw [IllegalArgumentException] or
 * [IllegalStateException] when the spec is built.
 */
internal object Targets {
    /** Spec of one class ([ClassTargetBuilder.multiple] = `false`) or several classes. */
    fun classes(id: String, block: ClassTargetBuilder.() -> Unit): TargetSpec<Class<*>> =
        ClassTargetBuilder(id).apply(block).build()

    /** Spec of a method, looked up among the methods declared by the candidate classes. */
    fun method(id: String, block: MethodTargetBuilder.() -> Unit): TargetSpec<Method> =
        MethodTargetBuilder(id).apply(block).build()

    /** Spec of a constructor of the candidate classes. */
    fun constructor(id: String, block: ConstructorTargetBuilder.() -> Unit): TargetSpec<Constructor<*>> =
        ConstructorTargetBuilder(id).apply(block).build()

    /** Spec of a field, looked up among the fields declared by the candidate classes. */
    fun field(id: String, block: FieldTargetBuilder.() -> Unit): TargetSpec<Field> =
        FieldTargetBuilder(id).apply(block).build()

    /** Ids are free-form but must be non-blank, without whitespace, and not `*` (the force-discovery wildcard). */
    internal fun checkId(id: String) {
        require(id.isNotEmpty() && id != "*" && id.none { it.isWhitespace() || it.isISOControl() }) {
            "Invalid target id '$id': it must be non-empty, contain no whitespace and not be '*'"
        }
    }

    internal fun checkClassName(id: String, name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && '/' !in trimmed && trimmed.none { it.isWhitespace() }) {
            "Target '$id': '$name' is not a binary class name (use a.b.Outer\$Inner)"
        }
        return trimmed
    }

    internal fun checkTypes(id: String, what: String, types: Array<out Class<*>?>): List<Class<*>> =
        types.mapIndexed { index, type ->
            requireNotNull(type) { "Target '$id': $what type #$index is null" }
        }
}

/** `Targets.classes(id) { ... }`. */
@TargetsDsl
internal class ClassTargetBuilder internal constructor(val id: String) {
    private val exact = ArrayList<String>()
    private var discovery: ClassDiscoveryBuilder? = null

    /** `false` (default): the first match. `true`: every exact candidate that exists, or every discovered class. */
    var multiple: Boolean = false

    /** Exact candidate classes, tried in order. May be called several times. */
    fun exact(vararg classNames: String) {
        classNames.mapTo(exact) { Targets.checkClassName(id, it) }
    }

    /** How to recognise the class by structure when no exact candidate exists. At most once. */
    fun discover(block: ClassDiscoveryBuilder.() -> Unit) {
        check(discovery == null) { "Target '$id': discover { } may be given once" }
        discovery = ClassDiscoveryBuilder(id).apply(block)
    }

    internal fun build(): TargetSpec<Class<*>> {
        Targets.checkId(id)
        check(exact.isNotEmpty() || discovery != null) { "Target '$id': give exact(...) candidates or discover { }" }
        val built = discovery?.build<Class<*>>(memberPredicate = null)
        return TargetSpec(id, TargetKind.CLASS, exact.distinct(), built, multiple, MemberQuery.Classes)
    }
}

/** `Targets.method(id) { ... }`. Call [params] or [anyParams]; the other settings are optional. */
@TargetsDsl
internal class MethodTargetBuilder internal constructor(val id: String) {
    private val exact = ArrayList<String>()
    private var discovery: MethodDiscoveryBuilder? = null
    private var paramsChosen = false
    private var params: List<Class<*>>? = null
    private var returnType: Class<*>? = null

    /** Method name; `null` (default) accepts any name (then rely on the signature and `methodWhere`). */
    var name: String? = null

    /** `true`: abstract methods (interface or abstract class declarations) never match. Default `false`. */
    var concreteOnly: Boolean = false

    /** `false` (default): the first match. `true`: every matching method of every matching class. */
    var multiple: Boolean = false

    /** Classes the method is known to be declared in, tried in order. May be called several times. */
    fun exactIn(vararg classNames: String) {
        classNames.mapTo(exact) { Targets.checkClassName(id, it) }
    }

    /** Exact parameter types (`params()` = no parameters). Nullable so `Int::class.javaPrimitiveType` fits. */
    fun params(vararg types: Class<*>?) {
        check(!paramsChosen) { "Target '$id': params(...) / anyParams() may be given once" }
        params = Targets.checkTypes(id, "parameter", types)
        paramsChosen = true
    }

    /** Accepts any parameter list (every overload with the other constraints). */
    fun anyParams() {
        check(!paramsChosen) { "Target '$id': params(...) / anyParams() may be given once" }
        params = null
        paramsChosen = true
    }

    /** Exact return type (`Void.TYPE` for `void`). Optional. */
    fun returns(type: Class<*>?) {
        check(returnType == null) { "Target '$id': returns(...) may be given once" }
        returnType = requireNotNull(type) { "Target '$id': return type is null" }
    }

    /** How to recognise the declaring class (and the method) by structure. At most once. */
    fun discover(block: MethodDiscoveryBuilder.() -> Unit) {
        check(discovery == null) { "Target '$id': discover { } may be given once" }
        discovery = MethodDiscoveryBuilder(id).apply(block)
    }

    internal fun build(): TargetSpec<Method> {
        Targets.checkId(id)
        check(exact.isNotEmpty() || discovery != null) { "Target '$id': give exactIn(...) candidates or discover { }" }
        check(paramsChosen) { "Target '$id': call params(...) or anyParams()" }
        name?.let { require(it.isNotBlank()) { "Target '$id': name is blank" } }
        val query = MemberQuery.Methods(name, params, returnType, concreteOnly)
        val built = discovery?.let { it.build(it.methodPredicate) }
        return TargetSpec(id, TargetKind.METHOD, exact.distinct(), built, multiple, query)
    }
}

/** `Targets.constructor(id) { ... }`. Call [params] or [anyParams]. */
@TargetsDsl
internal class ConstructorTargetBuilder internal constructor(val id: String) {
    private val exact = ArrayList<String>()
    private var discovery: ConstructorDiscoveryBuilder? = null
    private var paramsChosen = false
    private var params: List<Class<*>>? = null

    /** `false` (default): the first match. `true`: every matching constructor of every matching class. */
    var multiple: Boolean = false

    /** Classes whose constructors are wanted, tried in order. May be called several times. */
    fun exactIn(vararg classNames: String) {
        classNames.mapTo(exact) { Targets.checkClassName(id, it) }
    }

    /** Exact parameter types (`params()` = the no-argument constructor). */
    fun params(vararg types: Class<*>?) {
        check(!paramsChosen) { "Target '$id': params(...) / anyParams() may be given once" }
        params = Targets.checkTypes(id, "parameter", types)
        paramsChosen = true
    }

    /** Accepts any parameter list. */
    fun anyParams() {
        check(!paramsChosen) { "Target '$id': params(...) / anyParams() may be given once" }
        params = null
        paramsChosen = true
    }

    /** How to recognise the class (and the constructor) by structure. At most once. */
    fun discover(block: ConstructorDiscoveryBuilder.() -> Unit) {
        check(discovery == null) { "Target '$id': discover { } may be given once" }
        discovery = ConstructorDiscoveryBuilder(id).apply(block)
    }

    internal fun build(): TargetSpec<Constructor<*>> {
        Targets.checkId(id)
        check(exact.isNotEmpty() || discovery != null) { "Target '$id': give exactIn(...) candidates or discover { }" }
        check(paramsChosen) { "Target '$id': call params(...) or anyParams()" }
        val built = discovery?.let { it.build(it.constructorPredicate) }
        return TargetSpec(id, TargetKind.CONSTRUCTOR, exact.distinct(), built, multiple, MemberQuery.Constructors(params))
    }
}

/** `Targets.field(id) { ... }`. */
@TargetsDsl
internal class FieldTargetBuilder internal constructor(val id: String) {
    private val exact = ArrayList<String>()
    private var discovery: FieldDiscoveryBuilder? = null
    private var fieldType: Class<*>? = null

    /** Field name; `null` (default) accepts any name. */
    var name: String? = null

    /** `false` (default): the first match. `true`: every matching field of every matching class. */
    var multiple: Boolean = false

    /** Classes the field is known to be declared in, tried in order. May be called several times. */
    fun exactIn(vararg classNames: String) {
        classNames.mapTo(exact) { Targets.checkClassName(id, it) }
    }

    /** Exact field type. Optional. */
    fun type(type: Class<*>?) {
        check(fieldType == null) { "Target '$id': type(...) may be given once" }
        fieldType = requireNotNull(type) { "Target '$id': field type is null" }
    }

    /** How to recognise the class (and the field) by structure. At most once. */
    fun discover(block: FieldDiscoveryBuilder.() -> Unit) {
        check(discovery == null) { "Target '$id': discover { } may be given once" }
        discovery = FieldDiscoveryBuilder(id).apply(block)
    }

    internal fun build(): TargetSpec<Field> {
        Targets.checkId(id)
        check(exact.isNotEmpty() || discovery != null) { "Target '$id': give exactIn(...) candidates or discover { }" }
        name?.let { require(it.isNotBlank()) { "Target '$id': name is blank" } }
        val built = discovery?.let { it.build(it.fieldPredicate) }
        return TargetSpec(id, TargetKind.FIELD, exact.distinct(), built, multiple, MemberQuery.Fields(name, fieldType))
    }
}

/**
 * `discover { }` of a class spec: which class names to consider ([packages], [nameFilter]; at least one is
 * required, since discovery must never load every class of the host) and the structural test [where].
 */
@TargetsDsl
internal open class ClassDiscoveryBuilder internal constructor(protected val id: String) {
    private val packages = ArrayList<String>()
    private var nameFilter: Regex? = null
    private var classPredicate: ((Class<*>) -> Boolean)? = null

    /** Package prefixes (`com.whatsapp.settings`); a class matches its package or any subpackage. May repeat. */
    fun packages(vararg names: String) {
        names.mapTo(packages) { name ->
            val trimmed = name.trim().removeSuffix(".")
            require(trimmed.isNotEmpty() && '/' !in trimmed && trimmed.none { it.isWhitespace() }) {
                "Target '$id': '$name' is not a package name"
            }
            trimmed
        }
    }

    /**
     * Cheap prefilter on class names before any class is loaded: [Regex.containsMatchIn] on the binary name
     * (`a.b.Outer$Inner`); anchor it with `^`/`$` for a full match. At most once.
     */
    fun nameFilter(regex: Regex) {
        check(nameFilter == null) { "Target '$id': nameFilter(...) may be given once" }
        nameFilter = regex
    }

    /**
     * Structural test of a loaded candidate class (loaded without initialising it). Throwing counts as no match.
     * At most once.
     */
    fun where(predicate: (Class<*>) -> Boolean) {
        check(classPredicate == null) { "Target '$id': where { } may be given once" }
        classPredicate = predicate
    }

    internal fun <T : Any> build(memberPredicate: ((T) -> Boolean)?): TargetDiscovery<T> {
        check(packages.isNotEmpty() || nameFilter != null) {
            "Target '$id': discover { } needs packages(...) or nameFilter(...)"
        }
        return TargetDiscovery(packages.distinct(), nameFilter, classPredicate, memberPredicate)
    }
}

/** `discover { }` of a method spec: the class tests plus an optional [methodWhere]. */
@TargetsDsl
internal class MethodDiscoveryBuilder internal constructor(id: String) : ClassDiscoveryBuilder(id) {
    internal var methodPredicate: ((Method) -> Boolean)? = null
        private set

    /**
     * Extra test of each method of a discovered class that already satisfies the member constraints. Throwing
     * counts as no match. At most once.
     */
    fun methodWhere(predicate: (Method) -> Boolean) {
        check(methodPredicate == null) { "Target '$id': methodWhere { } may be given once" }
        methodPredicate = predicate
    }
}

/** `discover { }` of a constructor spec: the class tests plus an optional [constructorWhere]. */
@TargetsDsl
internal class ConstructorDiscoveryBuilder internal constructor(id: String) : ClassDiscoveryBuilder(id) {
    internal var constructorPredicate: ((Constructor<*>) -> Boolean)? = null
        private set

    /** Extra test of each constructor that already satisfies [ConstructorTargetBuilder.params]. At most once. */
    fun constructorWhere(predicate: (Constructor<*>) -> Boolean) {
        check(constructorPredicate == null) { "Target '$id': constructorWhere { } may be given once" }
        constructorPredicate = predicate
    }
}

/** `discover { }` of a field spec: the class tests plus an optional [fieldWhere]. */
@TargetsDsl
internal class FieldDiscoveryBuilder internal constructor(id: String) : ClassDiscoveryBuilder(id) {
    internal var fieldPredicate: ((Field) -> Boolean)? = null
        private set

    /** Extra test of each field that already satisfies the name and type constraints. At most once. */
    fun fieldWhere(predicate: (Field) -> Boolean) {
        check(fieldPredicate == null) { "Target '$id': fieldWhere { } may be given once" }
        fieldPredicate = predicate
    }
}
