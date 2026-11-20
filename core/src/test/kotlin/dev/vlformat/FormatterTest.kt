package dev.vlformat

import dev.vlformat.engine.Formatter
import dev.vlformat.lexer.Language
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatterTest {

    private val formatter = Formatter(discoverPlugins = false)

    private fun kt(src: String) = formatter.format(src, Language.KOTLIN)
    private fun java(src: String) = formatter.format(src, Language.JAVA)

    // ---- spacing -----------------------------------------------------------

    @Test
    fun `assignment gets spaces`() {
        assertEquals("val x = 1\n", kt("val x=1"))
    }

    @Test
    fun `parameters get comma and colon spacing`() {
        assertEquals("fun foo(a: Int, b: String) {}\n", kt("fun foo( a:Int ,b : String ){}"))
    }

    @Test
    fun `control keyword gets space before paren, call does not`() {
        assertEquals("if (ready()) go()\n", kt("if( ready() )go ()"))
        assertEquals("while (x == 0) tick();\n", java("while(x==0)tick () ;"))
    }

    @Test
    fun `kotlin class header colon breathes, type colon hugs left`() {
        assertEquals("class Foo : Bar() {}\n", kt("class Foo:Bar(){}"))
        assertEquals("val x: Int = 0\n", kt("val x : Int=0"))
        assertEquals("fun f(): Int = 1\n", kt("fun f() : Int=1"))
    }

    @Test
    fun `java ternary and case colons`() {
        assertEquals("int y = a ? b : c;\n", java("int y=a?b:c;"))
    }

    @Test
    fun `java enhanced for colon`() {
        assertEquals("for (String s : list) use(s);\n", java("for(String s:list)use(s);"))
    }

    @Test
    fun `elvis safe call and not-null`() {
        assertEquals("val a = b ?: c\n", kt("val a=b?:c"))
        assertEquals("val d = e?.f!!.g\n", kt("val d=e ?. f !! . g"))
    }

    @Test
    fun `kotlin nullable type`() {
        assertEquals("var s: String? = null\n", kt("var s:String ?=null"))
    }

    @Test
    fun `ranges hug`() {
        assertEquals("for (i in 1..10) {}\n", kt("for(i in 1 .. 10){}"))
    }

    @Test
    fun `unary operators hug operand`() {
        assertEquals("return -1\n", kt("return - 1"))
        assertEquals("val y = -x + 3\n", kt("val y=-x+3"))
        assertEquals("f(a, -b)\n", kt("f(a,-b)"))
        assertEquals("if (!ok) return\n", kt("if(! ok)return"))
    }

    @Test
    fun `increment stays attached`() {
        assertEquals("i++;\n", java("i ++ ;"))
        assertEquals("int z = a + ++b;\n", java("int z=a+ ++b;"))
    }

    @Test
    fun `angle brackets keep author spacing`() {
        // `Map<..>>=x` (glued) is not valid Kotlin: kotlinc lexes `>=`. The
        // spaced form normalizes; a glued `>=` is always kept as is.
        assertEquals("val m: Map<String, List<Int>> = mapOf()\n", kt("val m:Map<String,List<Int>> =mapOf()"))
        assertEquals("boolean t = a < b;\n", java("boolean t = a < b;"))
        assertEquals("int s = x >> 2;\n", java("int s = x >> 2;"))
    }

    @Test
    fun `annotations`() {
        assertEquals("@Test fun x() {}\n", kt("@ Test fun x(){}"))
        assertEquals("@file:JvmName(\"X\")\n", kt("@file:JvmName(\"X\")"))
    }

    @Test
    fun `lambda spacing`() {
        assertEquals("list.map { it * 2 }.sum()\n", kt("list.map{it*2}.sum()"))
    }

    @Test
    fun `empty braces stay tight`() {
        assertEquals("class A {}\n", kt("class A{ }"))
    }

    @Test
    fun `line comment keeps one space`() {
        assertEquals("val x = 1 // note\n", kt("val x = 1// note"))
    }

    @Test
    fun `string contents untouched`() {
        assertEquals("val s = \"a  +  b\"\n", kt("val s=\"a  +  b\""))
    }

    @Test
    fun `method reference`() {
        assertEquals("list.forEach(System.out::println);\n", java("list.forEach(System.out :: println);"))
    }

    // ---- indentation -------------------------------------------------------

    @Test
    fun `nested blocks indent by four`() {
        val input = "class A {\nfun b() {\ncall()\n}\n}"
        val expected = "class A {\n    fun b() {\n        call()\n    }\n}\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `open paren makes continuation`() {
        val input = "val x = foo(\nbar,\nbaz\n)"
        val expected = "val x = foo(\n        bar,\n        baz\n)\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `leading dot chain is a continuation`() {
        val input = "val y = a\n.b()\n.c()"
        val expected = "val y = a\n        .b()\n        .c()\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `trailing operator makes continuation`() {
        val input = "val z = a +\nb"
        val expected = "val z = a +\n        b\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `block opened on continuation line indents past the opener`() {
        val input = "class B {\nfun user(n: String) =\napply {\nthis.n = n\n}\n}"
        val expected = "class B {\n" +
            "    fun user(n: String) =\n" +
            "            apply {\n" +
            "                this.n = n\n" +
            "            }\n" +
            "}\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `multiline lambda in a dot chain keeps its body aligned`() {
        val input = "val x = items\n.map {\ntransform(it)\n}\n.sum()"
        val expected = "val x = items\n" +
            "        .map {\n" +
            "            transform(it)\n" +
            "        }\n" +
            "        .sum()\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `call opened on continuation line keeps args and closer past the opener`() {
        val input = "fun f() {\nval v =\ncanon(\nencoded = true,\n)\n}"
        val expected = "fun f() {\n" +
            "    val v =\n" +
            "            canon(\n" +
            "                    encoded = true,\n" +
            "            )\n" +
            "}\n"
        assertEquals(expected, kt(input))
    }

    @Test
    fun `java colon switch bodies indent an extra level`() {
        val input = "void f() {\nswitch (x) {\ncase 1:\nfoo();\nbreak;\ndefault:\nbar();\n}\n}"
        val expected = "void f() {\n" +
            "    switch (x) {\n" +
            "        case 1:\n" +
            "            foo();\n" +
            "            break;\n" +
            "        default:\n" +
            "            bar();\n" +
            "    }\n" +
            "}\n"
        assertEquals(expected, java(input))
    }

    @Test
    fun `kotlin when branches get no extra indent`() {
        val input = "fun f(x: Int) = when (x) {\n1 -> \"one\"\nelse -> \"many\"\n}"
        val expected = "fun f(x: Int) = when (x) {\n    1 -> \"one\"\n    else -> \"many\"\n}\n"
        assertEquals(expected, kt(input))
    }

    // ---- token rules -------------------------------------------------------

    @Test
    fun `blank lines collapse to one`() {
        val input = "fun a() {}\n\n\n\n\nfun b() {}"
        assertEquals("fun a() {}\n\nfun b() {}\n", kt(input))
    }

    @Test
    fun `no blank line after opening brace or before closing`() {
        val input = "class A {\n\n\nfun x() {}\n\n\n}"
        assertEquals("class A {\n    fun x() {}\n}\n", kt(input))
    }

    @Test
    fun `opening brace attaches to previous line`() {
        val input = "if (x)\n{\ny()\n}"
        assertEquals("if (x) {\n    y()\n}\n", kt(input))
    }

    @Test
    fun `else attaches to closing brace`() {
        val input = "if (x) {\na()\n}\nelse {\nb()\n}"
        assertEquals("if (x) {\n    a()\n} else {\n    b()\n}\n", kt(input))
    }

    @Test
    fun `catch and finally attach`() {
        val input = "try {\na()\n}\ncatch (e: Exception) {\nb()\n}\nfinally {\nc()\n}"
        assertEquals(
            "try {\n    a()\n} catch (e: Exception) {\n    b()\n} finally {\n    c()\n}\n",
            kt(input),
        )
    }

    @Test
    fun `do-while attaches, plain while does not`() {
        val doWhile = "do {\nx();\n}\nwhile (a);"
        assertEquals("do {\n    x();\n} while (a);\n", java(doWhile))

        val plain = "void g() {\nif (a) {\n}\nwhile (b) {\nc();\n}\n}"
        assertEquals(
            "void g() {\n    if (a) {\n    }\n    while (b) {\n        c();\n    }\n}\n",
            java(plain),
        )
    }

    @Test
    fun `when branch else is not attached to closing brace`() {
        val input = "when (x) {\n\"a\" -> {\nf()\n}\nelse -> g()\n}"
        assertEquals("when (x) {\n    \"a\" -> {\n        f()\n    }\n    else -> g()\n}\n", kt(input))
    }

    @Test
    fun `comment blocks brace attachment`() {
        val input = "if (x) // why\n{\ny()\n}"
        assertEquals("if (x) // why\n{\n    y()\n}\n", kt(input))
    }

    // ---- whole-file hygiene ------------------------------------------------

    @Test
    fun `trailing whitespace removed and final newline added`() {
        assertEquals("val x = 1\n", kt("val x = 1   "))
        assertEquals("val x = 1\n", kt("val x = 1\n\n\n"))
    }

    @Test
    fun `leading blank lines removed`() {
        assertEquals("val x = 1\n", kt("\n\n\nval x = 1"))
    }

    @Test
    fun `kdoc star gutter realigned`() {
        val input = "class A {\n/**\n     * Docs.\n */\nfun x() {}\n}"
        val expected = "class A {\n    /**\n     * Docs.\n     */\n    fun x() {}\n}\n"
        assertEquals(expected, kt(input))
    }
}
