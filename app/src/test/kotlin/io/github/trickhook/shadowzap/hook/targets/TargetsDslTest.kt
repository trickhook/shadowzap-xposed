package io.github.trickhook.shadowzap.hook.targets

import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TargetsDslTest {
    @Test
    fun `class spec keeps candidates, discovery and multiplicity`() {
        val predicate: (Class<*>) -> Boolean = { it.simpleName.endsWith("Theme") }
        val spec = Targets.classes("theme.variants") {
            exact("org.sample.host.theme.DarkerTheme", " org.sample.host.theme.LightTheme ")
            exact("org.sample.host.theme.DarkerTheme")
            discover {
                packages("org.sample.host.theme.")
                nameFilter(Regex("Theme$"))
                where(predicate)
            }
            multiple = true
        }
        assertEquals("theme.variants", spec.id)
        assertEquals(TargetKind.CLASS, spec.kind)
        assertEquals(listOf("org.sample.host.theme.DarkerTheme", "org.sample.host.theme.LightTheme"), spec.exactClassNames)
        assertTrue(spec.multiple)
        val discovery = assertNotNull(spec.discovery)
        assertEquals(listOf("org.sample.host.theme"), discovery.packages)
        assertEquals("Theme$", discovery.nameFilter?.pattern)
        assertSame(predicate, discovery.classPredicate)
        assertNull(discovery.memberPredicate)
        assertEquals("", spec.constraints)
    }

    @Test
    fun `method spec keeps its member constraints`() {
        val methodPredicate: (Method) -> Boolean = { !it.isVarArgs }
        val spec = Targets.method("script.fromFile") {
            exactIn("com.facebook.react.runtime.ReactInstance\$loadJSBundle\$1")
            name = "loadScriptFromFile"
            params(String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            returns(Void.TYPE)
            concreteOnly = true
            discover {
                packages("com.facebook.react.runtime", "com.facebook.react.bridge")
                where { true }
                methodWhere(methodPredicate)
            }
        }
        assertEquals(TargetKind.METHOD, spec.kind)
        assertFalse(spec.multiple)
        val query = spec.query as MemberQuery.Methods
        assertEquals("loadScriptFromFile", query.name)
        assertEquals(listOf(String::class.java, String::class.java, java.lang.Boolean.TYPE), query.params)
        assertEquals(Void.TYPE, query.returns)
        assertTrue(query.concreteOnly)
        assertSame(methodPredicate, spec.discovery?.memberPredicate)
        assertEquals("#loadScriptFromFile(String, String, boolean): void [concrete]", spec.constraints)
    }

    @Test
    fun `anyParams leaves parameters free and constructor and field specs build`() {
        val method = Targets.method("m") {
            exactIn("a.B")
            anyParams()
        }
        val methodQuery = method.query as MemberQuery.Methods
        assertNull(methodQuery.params)
        assertNull(methodQuery.name)
        assertEquals("#*(..)", method.constraints)

        val constructor = Targets.constructor("c") {
            exactIn("a.B")
            anyParams()
            multiple = true
        }
        assertEquals(TargetKind.CONSTRUCTOR, constructor.kind)
        assertEquals("#<init>(..)", constructor.constraints)

        val noArg = Targets.constructor("c0") {
            exactIn("a.B")
            params()
        }
        assertEquals(emptyList(), (noArg.query as MemberQuery.Constructors).params)

        val field = Targets.field("f") {
            exactIn("a.B")
            name = "INSTANCE"
            type(String::class.java)
            discover { nameFilter(Regex("^a\\.")) }
        }
        assertEquals(TargetKind.FIELD, field.kind)
        assertEquals("#INSTANCE: String", field.constraints)
    }

    @Test
    fun `discovery name filter matches packages, subpackages and the regex`() {
        val discovery = assertNotNull(
            Targets.classes("d") {
                discover {
                    packages("com.facebook.react")
                    nameFilter(Regex("FontManager"))
                }
            }.discovery,
        )
        assertTrue(discovery.acceptsName("com.facebook.react.views.text.ReactFontManager"))
        assertTrue(discovery.acceptsName("com.facebook.react.common.assets.ReactFontManager\$Companion"))
        assertFalse(discovery.acceptsName("com.facebook.reactnative.FontManager"), "sibling prefix is not a subpackage")
        assertFalse(discovery.acceptsName("com.facebook.react"), "the package itself is not a class")
        assertFalse(discovery.acceptsName("com.facebook.react.views.text.ReactTextView"))

        val byName = assertNotNull(
            Targets.classes("n") { discover { nameFilter(Regex("\\\$loadJSBundle\\\$\\d+$")) } }.discovery,
        )
        assertTrue(byName.acceptsName("com.facebook.react.runtime.ReactInstance\$loadJSBundle\$1"))
        assertFalse(byName.acceptsName("com.facebook.react.runtime.ReactInstance"))
    }

    @Test
    fun `mistakes in a spec fail when it is built`() {
        assertFailsWith<IllegalArgumentException> { Targets.classes(" ") { exact("a.B") } }
        assertFailsWith<IllegalArgumentException> { Targets.classes("has space") { exact("a.B") } }
        assertFailsWith<IllegalArgumentException> { Targets.classes("*") { exact("a.B") } }
        assertFailsWith<IllegalArgumentException> { Targets.classes("slash") { exact("a/B") } }
        assertFailsWith<IllegalStateException> { Targets.classes("empty") { multiple = true } }
        assertFailsWith<IllegalStateException> { Targets.classes("broad") { discover { where { true } } } }
        assertFailsWith<IllegalStateException> {
            Targets.classes("twice") {
                discover { packages("a") }
                discover { packages("b") }
            }
        }
        assertFailsWith<IllegalStateException> { Targets.method("noParams") { exactIn("a.B") } }
        assertFailsWith<IllegalStateException> {
            Targets.method("bothParams") {
                exactIn("a.B")
                params(String::class.java)
                anyParams()
            }
        }
        assertFailsWith<IllegalArgumentException> {
            Targets.method("nullParam") {
                exactIn("a.B")
                params(String::class.java, null)
            }
        }
        assertFailsWith<IllegalStateException> { Targets.constructor("noCandidates") { anyParams() } }
        assertFailsWith<IllegalStateException> {
            Targets.field("typeTwice") {
                exactIn("a.B")
                type(String::class.java)
                type(Int::class.javaPrimitiveType)
            }
        }
    }
}
