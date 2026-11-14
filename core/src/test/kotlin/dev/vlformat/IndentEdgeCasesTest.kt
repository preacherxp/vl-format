package dev.vlformat

import dev.vlformat.engine.Formatter
import dev.vlformat.lexer.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndentEdgeCasesTest {

    private val formatter = Formatter(discoverPlugins = false)

    private fun kt(src: String) = formatter.format(src, Language.KOTLIN)
    private fun java(src: String) = formatter.format(src, Language.JAVA)

    @Test
    fun `else-if chain stays flat`() {
        val input = "if (a) {\nx()\n}\nelse if (b) {\ny()\n}\nelse {\nz()\n}"
        val expected = "if (a) {\n    x()\n} else if (b) {\n    y()\n} else {\n    z()\n}\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `nested colon switches`() {
        val input = "switch (a) {\ncase 1:\nswitch (b) {\ncase 2:\ndeep();\n}\nbreak;\n}"
        val expected = "switch (a) {\n" +
            "    case 1:\n" +
            "        switch (b) {\n" +
            "            case 2:\n" +
            "                deep();\n" +
            "        }\n" +
            "        break;\n" +
            "}\n"
        assertEquals(expected, java(input))
    }

    @Test
    fun `when nested in when gets plain block indent`() {
        val input = "val r = when (a) {\n1 -> when (b) {\n2 -> \"x\"\nelse -> \"y\"\n}\nelse -> \"z\"\n}"
        val expected = "val r = when (a) {\n" +
            "    1 -> when (b) {\n" +
            "        2 -> \"x\"\n" +
            "        else -> \"y\"\n" +
            "    }\n" +
            "    else -> \"z\"\n" +
            "}\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `multiline condition is a continuation, body is not`() {
        val input = "if (a &&\nb) {\nrun()\n}"
        val expected = "if (a &&\n        b) {\n    run()\n}\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `comment lines get block indent`() {
        val input = "fun f() {\n// step one\na()\n/* step two */\nb()\n}"
        val expected = "fun f() {\n    // step one\n    a()\n    /* step two */\n    b()\n}\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `multiline array initializer indents as a block`() {
        val input = "int[] a = {\n1,\n2,\n};"
        val expected = "int[] a = {\n    1,\n    2,\n};\n"
        assertEquals(expected, java(input))
    }

    @Test
    fun `anonymous class body indents past declaration`() {
        val input = "Runnable r = new Runnable() {\npublic void run() {\nwork();\n}\n};"
        val expected = "Runnable r = new Runnable() {\n" +
            "    public void run() {\n" +
            "        work();\n" +
            "    }\n" +
            "};\n"
        assertEquals(expected, java(input))
    }

    @Test
    fun `lambda passed among call args`() {
        val input = "foo(bar, {\nbody()\n})"
        val expected = "foo(bar, {\n    body()\n})\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `multiline chain of multiline lambdas`() {
        val input = "val r = items\n.map {\nit * 2\n}\n.filter { it > 0 }"
        val expected = "val r = items\n" +
            "        .map {\n" +
            "            it * 2\n" +
            "        }\n" +
            "        .filter { it > 0 }\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `multiline parameter list closes back to declaration`() {
        val input = "fun combine(\na: Int,\nb: Int,\n): Int {\nreturn a + b\n}"
        val expected = "fun combine(\n        a: Int,\n        b: Int,\n): Int {\n    return a + b\n}\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `fifty levels of nesting stay linear and do not crash`() {
        val depth = 50
        val input = buildString {
            repeat(depth) { append("if (x) {\n") }
            append("core()\n")
            repeat(depth) { append("}\n") }
        }
        val out = kt(input)
        assertTrue(out.contains(" ".repeat(depth * 4) + "core()"), "innermost line should be at depth*4")
        assertEquals(out, kt(out), "must stay idempotent at depth")
    }

    @Test
    fun `stray closing braces clamp at column zero`() {
        val input = "}\n}\nval x = 1"
        val out = kt(input)
        assertEquals("}\n}\nval x = 1\n", out)
        assertEquals(out, kt(out))
    }

    @Test
    fun `unclosed paren makes following lines continuations but never crashes`() {
        val input = "val x = foo(\na,\nb"
        val out = kt(input)
        assertEquals("val x = foo(\n        a,\n        b\n", out)
        assertEquals(out, kt(out))
    }

    @Test
    fun `tab indented input is converted to spaces`() {
        val input = "class A {\n\tfun b() {\n\t\tc()\n\t}\n}"
        assertEquals("class A {\n    fun b() {\n        c()\n    }\n}\n", kt(input))
    }

    @Test
    fun `crlf input renders as lf`() {
        assertEquals("fun a() {\n    b()\n}\n", kt("fun a() {\r\nb()\r\n}\r\n"))
    }

    @Test
    fun `expression body wrapped after equals`() {
        val input = "fun big(): Int =\ncompute() +\nmore()"
        val expected = "fun big(): Int =\n        compute() +\n        more()\n"
        assertEquals(expected, kt(input))
    }
}
