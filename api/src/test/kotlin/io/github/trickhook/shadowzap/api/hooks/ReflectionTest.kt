package io.github.trickhook.shadowzap.api.hooks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("unused")
private open class Base {
    private val secret: String = "base"
    protected open fun greet(name: String): String = "hello $name"
}

@Suppress("unused")
private class Child(val value: Int) : Base() {
    constructor() : this(0)

    fun overloaded(a: Int): Int = a
    fun overloaded(a: String): String = a
    fun unique(values: Array<String>): Int = values.size

    companion object {
        @JvmField
        val STATIC_VALUE: String = "static"
    }
}

class ReflectionTest {
    private val loader = ReflectionTest::class.java.classLoader!!

    @Test
    fun `methods are found up the hierarchy and made accessible`() {
        val greet = Child::class.java.requireMethod("greet", String::class.java)
        assertEquals("hello x", greet.invoke(Child(), "x"))
        assertNull(Child::class.java.methodOrNull("greet", Int::class.javaPrimitiveType!!))
    }

    @Test
    fun `missing methods list the candidates`() {
        val error = assertFailsWith<HookTargetNotFoundException> {
            Child::class.java.requireMethod("overloaded", Long::class.javaPrimitiveType!!)
        }
        assertTrue(error.message!!.contains("overloaded(int)"), error.message)
        assertTrue(error.message!!.contains("overloaded(java.lang.String)"), error.message)
    }

    @Test
    fun `lookup by unique name`() {
        assertEquals("unique", Child::class.java.requireMethodByName("unique").name)
        assertFailsWith<HookTargetNotFoundException> { Child::class.java.requireMethodByName("overloaded") }
        assertFailsWith<HookTargetNotFoundException> { Child::class.java.requireMethodByName("absent") }
    }

    @Test
    fun `constructors and fields`() {
        assertEquals(5, (Child::class.java.requireConstructor(Int::class.javaPrimitiveType!!).newInstance(5)).value)
        assertFailsWith<HookTargetNotFoundException> { Child::class.java.requireConstructor(String::class.java) }
        assertEquals("base", Child::class.java.requireField("secret").get(Child()))
        assertEquals("static", Child::class.java.readStaticField("STATIC_VALUE"))
    }

    @Test
    fun `classes and type names resolve`() {
        assertNull(loader.classOrNull("does.not.Exist"))
        assertFailsWith<HookTargetNotFoundException> { loader.requireClass("does.not.Exist") }
        assertEquals<Class<*>>(String::class.java, loader.requireAnyClass("does.not.Exist", "java.lang.String"))
        val types = loader.resolveTypes("int", "java.lang.String[]", "boolean[][]")
        assertEquals<Class<*>?>(Int::class.javaPrimitiveType, types[0])
        assertEquals<Class<*>>(Array<String>::class.java, types[1])
        assertEquals<Class<*>>(Array<BooleanArray>::class.java, types[2])
    }
}
