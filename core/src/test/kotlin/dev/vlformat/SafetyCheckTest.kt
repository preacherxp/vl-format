package dev.vlformat

import dev.vlformat.engine.Formatter
import dev.vlformat.engine.SafetyCheck
import dev.vlformat.lexer.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetyCheckTest {

    private val formatter = Formatter(discoverPlugins = false)

    @Test
    fun `formatted output is token-equivalent to input`() {
        val messy = "class   A{fun b( x:Int ):Int{return x*2}}"
        val formatted = formatter.format(messy, Language.KOTLIN)
        assertNull(SafetyCheck.verify(messy, formatted, Language.KOTLIN))
    }

    @Test
    fun `dropped token is caught`() {
        assertNotNull(SafetyCheck.verify("val x = 1 + 2", "val x = 1", Language.KOTLIN))
    }

    @Test
    fun `changed literal is caught`() {
        val problem = SafetyCheck.verify("val s = \"a\"", "val s = \"b\"", Language.KOTLIN)
        assertNotNull(problem)
        assertTrue(problem.contains("\"a\""), problem)
    }

    @Test
    fun `line moves and whitespace changes are fine`() {
        assertNull(SafetyCheck.verify("if (x)\n{\ny()\n}", "if (x) {\n    y()\n}\n", Language.KOTLIN))
    }

    @Test
    fun `comment gutter realignment is fine, comment rewording is not`() {
        assertNull(SafetyCheck.verify("/**\n      * hi\n */", "/**\n * hi\n */", Language.JAVA))
        assertNotNull(SafetyCheck.verify("/* hi */", "/* bye */", Language.JAVA))
    }

    @Test
    fun `verify passes for every fixture`() {
        val dir = java.nio.file.Paths.get(checkNotNull(javaClass.getResource("/fixtures")).toURI())
        java.nio.file.Files.walk(dir).use { s ->
            for (input in s.filter { it.fileName.toString().contains(".input.") }.toList()) {
                val language = checkNotNull(Language.fromFileName(input.fileName.toString()))
                val source = java.nio.file.Files.readString(input)
                val formatted = formatter.format(source, language)
                assertEquals(null, SafetyCheck.verify(source, formatted, language), "fixture $input")
            }
        }
    }
}
