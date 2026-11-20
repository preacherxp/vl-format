package dev.vlformat

import dev.vlformat.engine.Formatter
import dev.vlformat.engine.SafetyCheck
import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Lexer
import dev.vlformat.lexer.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regressions found by formatting real code (OkHttp, Guava, kotlinx.coroutines)
 * and checking the result with the real Kotlin and Java parsers. Each test is
 * one bug that produced code that no longer compiled or meant something else,
 * or a layout that real code commonly uses and the formatter flattened.
 */
class CorpusRegressionTest {

    private val formatter = Formatter(discoverPlugins = false)

    private fun kt(src: String) = formatter.format(src, Language.KOTLIN)
    private fun java(src: String) = formatter.format(src, Language.JAVA)

    private fun texts(src: String, lang: Language) =
        Lexer(src, lang).tokenize().filter { it.type != TokenType.NEWLINE }.map { it.text }

    // ---- lexer ---------------------------------------------------------------

    @Test
    fun `java hexadecimal floating point literals are one token`() {
        // Guava Hashing.java: `0x1.0p31` was split and rendered as `0x1.0 p31`.
        assertEquals(listOf("x", "=", "0x1.0p31", "/", "0x1p-3", ";"), texts("x = 0x1.0p31 / 0x1p-3;", Language.JAVA))
        assertEquals("double d = 0x1.8p3 + 0x1.0P+3d;\n", java("double d=0x1.8p3+0x1.0P+3d;"))
    }

    @Test
    fun `kotlin hex literal followed by a call is not a float`() {
        assertEquals(listOf("0xFF", ".", "toInt", "(", ")"), texts("0xFF.toInt()", Language.KOTLIN))
    }

    @Test
    fun `leading dot decimals are numbers`() {
        assertEquals("return .5;\n", java("return .5;"))
        assertEquals("double x = .5 + a.b;\n", java("double x=.5+a.b;"))
    }

    @Test
    fun `kotlin multi-dollar strings are one token`() {
        // kotlinx.coroutines: `$$"..."` was lexed as identifier `$$` + string
        // and rendered as `$$ "..."`, which does not compile.
        assertEquals(listOf("@", "JvmName", "(", "$$\"runTest\$default\"", ")"), texts("@JvmName($$\"runTest\$default\")", Language.KOTLIN))
        assertEquals("val s = $$\"a \${b} $\${c + 1}\"\n", kt("val s=$$\"a \${b} $\${c + 1}\""))
        assertEquals("val r = $$\"\"\"x \${y}\"\"\"\n", kt("val r=$$\"\"\"x \${y}\"\"\""))
    }

    @Test
    fun `single dollar template rules unchanged`() {
        assertEquals(listOf("val", "s", "=", "\"\$\${x}\$\""), texts("val s = \"\$\${x}\$\"", Language.KOTLIN))
    }

    // ---- line joins (Kotlin is newline sensitive) ------------------------------

    @Test
    fun `kotlin catch call on its own line is not a try clause`() {
        // kotlinx.coroutines tests: `}` + newline + `catch<Throwable> { }` is a
        // Flow.catch call; joining it to the `}` gave `} catch<Throwable> {`.
        val src = "launch {\n    onEach {\n        x()\n    }\n    catch<Throwable> { y() }\n}\n"
        assertEquals(src, kt(src))
    }

    @Test
    fun `kotlin finally call after a lambda is not a try clause`() {
        val src = "flow {\n    catch<Throwable> { e = it }\n    finally { done = true }\n}\n"
        assertEquals(src, kt(src))
    }

    @Test
    fun `kotlin try catch finally still attach`() {
        assertEquals(
            "try {\n    a()\n} catch (e: Exception) {\n    b()\n} finally {\n    c()\n}\n",
            kt("try {\na()\n}\ncatch (e: Exception) {\nb()\n}\nfinally {\nc()\n}"),
        )
    }

    @Test
    fun `kotlin lambda after a call on the next line is not attached`() {
        // `foo()` + newline + `{ }` is a call followed by a lambda, not a
        // trailing-lambda call; attaching the brace changes the program.
        assertEquals("fun f() {\n    foo()\n    { println() }\n    run\n    { 1 }\n}\n", kt("fun f() {\nfoo()\n{ println() }\nrun\n{ 1 }\n}"))
    }

    @Test
    fun `kotlin declaration and control braces still attach`() {
        assertEquals("fun f(): Int {\n    return 1\n}\n", kt("fun f(): Int\n{\nreturn 1\n}"))
        assertEquals("class A : B() {\n}\n", kt("class A : B()\n{\n}"))
        assertEquals("companion object {\n}\n", kt("companion object\n{\n}"))
        assertEquals("val v = if (a) {\n    1\n} else {\n    2\n}\n", kt("val v = if (a)\n{\n1\n}\nelse\n{\n2\n}"))
    }

    @Test
    fun `java braces attach as before`() {
        assertEquals("void f() {\n    foo();\n}\n", java("void f()\n{\nfoo();\n}"))
    }

    // ---- spacing -------------------------------------------------------------

    @Test
    fun `glued greater-or-equal is never split`() {
        // Old exception spaced `>` `=` after a "type argument list", which also
        // matched `check(a<b, c>=d)` and produced `c> = d`.
        assertEquals("check(a<b, c>=d);\n", java("check(a<b, c>=d);"))
        assertEquals("val ok = a>=b\n", kt("val ok = a>=b"))
    }

    @Test
    fun `kotlin soft keywords in call position take no space`() {
        // kotlinx.coroutines tests call `expect(1)`; it was rendered `expect (1)`.
        assertEquals("expect(1)\ndata(2)\nvalue(3)\n", kt("expect(1)\ndata (2)\nvalue( 3 )"))
        assertEquals("expect fun platform(): String\n", kt("expect fun platform(): String"))
        assertEquals("val g: suspend () -> Unit = {}\n", kt("val g: suspend () -> Unit = {}"))
    }

    @Test
    fun `comparison before a parenthesis keeps its space`() {
        assertEquals("if (a > (b + 1)) go();\n", java("if (a > (b + 1)) go();"))
        assertEquals("val m = HashMap<String, Int>()\n", kt("val m = HashMap<String, Int> ()"))
    }

    @Test
    fun `colon after a brace-less kotlin declaration line`() {
        // `expect class R` (no body) on an earlier line made a later
        // `): R {` look like a class header and become `) : R {`.
        val src = "expect class R\n\nfun f(\n        x: Int,\n): R {\n}\n"
        assertEquals(src, kt(src))
    }

    @Test
    fun `kotlin wildcard imports are not continuation lines`() {
        val src = "import a.b.*\nimport c.d.*\nimport e.F\n"
        assertEquals(src, kt(src))
    }

    @Test
    fun `not-null assertion before an infix keyword or comparison keeps its gap`() {
        // Ktor: `x!! in y` became `x!!in y` and `x!! == y` became `x!!== y`.
        assertEquals("check(a!! in set)\n", kt("check(a!! in set)"))
        assertEquals("return p[n]!! == \"#bot\"\n", kt("return p[n]!!== \"#bot\""))
        assertEquals("val c = x!!.y!!(1)!![0]\n", kt("val c = x!! .y!! (1)!! [0]"))
        assertNotNull(SafetyCheck.verify("a!! in b", "a!!in b", Language.KOTLIN))
    }

    @Test
    fun `kotlin label gap before at sign is kept`() {
        assertEquals("return@forEach\n", kt("return@forEach"))
        assertEquals("val x = foo @Ann bar\n", kt("val x = foo @Ann bar"))
    }

    // ---- indentation ---------------------------------------------------------

    @Test
    fun `brace-less control bodies are indented`() {
        assertEquals(
            "void f() {\n    if (x)\n        return;\n    else\n        go();\n    for (int i = 0; i < n; i++)\n        use(i);\n}\n",
            java("void f() {\nif (x)\nreturn;\nelse\ngo();\nfor (int i = 0; i < n; i++)\nuse(i);\n}"),
        )
    }

    @Test
    fun `wrapped condition anchors the brace-less body to the if line`() {
        assertEquals(
            "fun f() {\n    if (a &&\n            b)\n        go()\n}\n",
            kt("fun f() {\nif (a &&\nb)\ngo()\n}"),
        )
    }

    @Test
    fun `when branch body on the next line is indented`() {
        assertEquals(
            "when (x) {\n    1 ->\n        one()\n    else -> other()\n}\n",
            kt("when (x) {\n1 ->\none()\nelse -> other()\n}"),
        )
    }

    @Test
    fun `lambda parameters arrow does not double indent`() {
        assertEquals("list.forEach { item ->\n    use(item)\n}\n", kt("list.forEach { item ->\nuse(item)\n}"))
    }

    @Test
    fun `kotlin do-while condition does not open a body`() {
        assertEquals("do {\n    x()\n} while (y)\nz()\n", kt("do {\nx()\n} while (y)\nz()"))
    }

    @Test
    fun `kotlin property accessors are indented under the property`() {
        assertEquals(
            "class A {\n    var v: Int = 0\n        get() = field\n        private set\n" +
                "    val w: Int\n        get() {\n            return 1\n        }\n    fun f() = 1\n}\n",
            kt("class A {\nvar v: Int = 0\nget() = field\nprivate set\nval w: Int\nget() {\nreturn 1\n}\nfun f() = 1\n}"),
        )
    }

    @Test
    fun `set call inside a function is not an accessor`() {
        assertEquals("fun f() {\n    val x = 1\n    set(x)\n}\n", kt("fun f() {\nval x = 1\nset(x)\n}"))
    }

    // ---- safety check --------------------------------------------------------

    @Test
    fun `safety check refuses split greater-or-equal`() {
        assertNotNull(SafetyCheck.verify("check(a<b, c>=d);", "check(a<b, c> = d);", Language.JAVA))
        assertNotNull(SafetyCheck.verify("val x = a >>2", "val x = a > >2", Language.KOTLIN))
    }

    @Test
    fun `safety check refuses moved line breaks`() {
        assertNotNull(SafetyCheck.verify("val a = b\n-c", "val a = b -c", Language.KOTLIN))
        assertNotNull(SafetyCheck.verify("return x", "return\nx", Language.KOTLIN))
    }

    @Test
    fun `safety check refuses attaching a lambda in kotlin but not in java`() {
        assertNotNull(SafetyCheck.verify("foo()\n{ 1 }", "foo() { 1 }", Language.KOTLIN))
        assertNull(SafetyCheck.verify("void f()\n{ }", "void f() { }", Language.JAVA))
    }

    @Test
    fun `safety check refuses a kotlin label losing or gaining its gap`() {
        assertNotNull(SafetyCheck.verify("return@lit", "return @lit", Language.KOTLIN))
        assertNotNull(SafetyCheck.verify("foo @Ann bar", "foo@Ann bar", Language.KOTLIN))
        assertNotNull(SafetyCheck.verify("x !in y", "x ! in y", Language.KOTLIN))
    }

    @Test
    fun `safety check allows the legal joins`() {
        assertNull(SafetyCheck.verify("try {\n}\ncatch (e: E) {\n}", "try {\n} catch (e: E) {\n}", Language.KOTLIN))
        assertNull(SafetyCheck.verify("if (x)\n{\n}\nelse\n{\n}", "if (x) {\n} else {\n}", Language.KOTLIN))
    }
}
