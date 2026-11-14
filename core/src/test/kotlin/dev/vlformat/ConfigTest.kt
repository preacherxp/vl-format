package dev.vlformat

import dev.vlformat.config.FormatConfig
import dev.vlformat.engine.Formatter
import dev.vlformat.lexer.Language
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {

    @Test
    fun `properties parsing with defaults`() {
        val props = Properties().apply {
            setProperty("indentSize", "2")
            setProperty("maxLineLength", "120")
            setProperty("maxBlankLines", "3")
            setProperty("attachBraces", "false")
            setProperty("disabledRules", "blank-lines , attach-clauses")
        }
        val config = FormatConfig.fromProperties(props)
        assertEquals(2, config.indentSize)
        assertEquals(120, config.maxLineLength)
        assertEquals(3, config.maxBlankLines)
        assertEquals(false, config.attachBraces)
        assertEquals(setOf("blank-lines", "attach-clauses"), config.disabledRules)
        assertEquals(8, config.continuationIndent) // untouched default
    }

    @Test
    fun `discover walks up the directory tree`() {
        val root = Files.createTempDirectory("vlfmt")
        root.resolve(FormatConfig.FILE_NAME).writeText("indentSize=3\n")
        val nested = root.resolve("a/b/c").createDirectories()
        assertEquals(3, FormatConfig.discover(nested).indentSize)
        // No config anywhere above an isolated dir -> defaults.
        val lonely = Files.createTempDirectory("vlfmt-lonely")
        assertEquals(FormatConfig(), FormatConfig.discover(lonely))
    }

    @Test
    fun `indentSize is honored`() {
        val f = Formatter(FormatConfig(indentSize = 2, continuationIndent = 4), discoverPlugins = false)
        assertEquals("class A {\n  fun b() {\n    c()\n  }\n}\n", f.format("class A {\nfun b() {\nc()\n}\n}", Language.KOTLIN))
    }

    @Test
    fun `attachBraces can be disabled`() {
        val f = Formatter(FormatConfig(attachBraces = false), discoverPlugins = false)
        assertEquals("if (x)\n{\n    y()\n}\n", f.format("if (x)\n{\ny()\n}", Language.KOTLIN))
    }

    @Test
    fun `rules can be disabled by name`() {
        val f = Formatter(FormatConfig(disabledRules = setOf("blank-lines")), discoverPlugins = false)
        assertEquals("val a = 1\n\n\n\nval b = 2\n", f.format("val a = 1\n\n\n\nval b = 2", Language.KOTLIN))
    }
}
