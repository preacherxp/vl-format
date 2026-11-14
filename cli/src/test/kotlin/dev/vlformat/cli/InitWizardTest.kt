package dev.vlformat.cli

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InitWizardTest {

    private class Run(val code: Int, val out: String, val err: String)

    private fun cli(stdin: String, vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(
            arrayOf(*args),
            PrintStream(out, true),
            PrintStream(err, true),
            BufferedReader(StringReader(stdin)),
        )
        return Run(code, out.toString(), err.toString())
    }

    private fun tempDir(): Path = Files.createTempDirectory("vlfmt-init")

    @Test
    fun `all defaults on enter`() {
        val dir = tempDir()
        val run = cli("\n".repeat(8), "init", dir.toString())
        assertEquals(0, run.code, run.err)
        val config = dir.resolve(".vlformat").readText()
        assertTrue(config.contains("indentSize=4"), config)
        assertTrue(config.contains("continuationIndent=8"), config)
        assertTrue(config.contains("maxLineLength=120"), config)
        assertTrue(config.contains("maxBlankLines=1"), config)
        assertTrue(config.contains("attachBraces=true"), config)
        assertTrue(config.contains("insertFinalNewline=true"), config)
    }

    @Test
    fun `custom answers respected and continuation default follows indent`() {
        val dir = tempDir()
        // indent=2, continuation default becomes 4 (Enter), maxLen=100, blanks=2,
        // braces=n, clauses=y, stars=n, newline=y
        val run = cli("2\n\n100\n2\nn\ny\nn\ny\n", "init", dir.toString())
        assertEquals(0, run.code, run.err)
        val config = dir.resolve(".vlformat").readText()
        assertTrue(config.contains("indentSize=2"), config)
        assertTrue(config.contains("continuationIndent=4"), config)
        assertTrue(config.contains("maxLineLength=100"), config)
        assertTrue(config.contains("maxBlankLines=2"), config)
        assertTrue(config.contains("attachBraces=false"), config)
        assertTrue(config.contains("attachClauses=true"), config)
        assertTrue(config.contains("alignCommentStars=false"), config)
    }

    @Test
    fun `invalid answers reprompt`() {
        val dir = tempDir()
        val run = cli("banana\n3\n\n\n\nmaybe\ny\n\n\n\n", "init", dir.toString())
        assertEquals(0, run.code, run.err)
        assertTrue(run.out.contains("Please enter a number"))
        assertTrue(run.out.contains("Please answer y or n"))
        assertTrue(dir.resolve(".vlformat").readText().contains("indentSize=3"))
    }

    @Test
    fun `declining overwrite aborts`() {
        val dir = tempDir()
        dir.resolve(".vlformat").writeText("indentSize=7\n")
        val run = cli("n\n", "init", dir.toString())
        assertEquals(1, run.code)
        assertEquals("indentSize=7\n", dir.resolve(".vlformat").readText())
    }

    @Test
    fun `generated config drives the formatter`() {
        val dir = tempDir()
        assertEquals(0, cli("2\n\n0\n\n\n\n\n\n", "init", dir.toString()).code)
        Files.createDirectories(dir.resolve("src"))
        dir.resolve("src/A.kt").writeText("class A {\nfun x() {\ny()\n}\n}\n")
        assertEquals(0, cli("", dir.toString()).code)
        assertEquals("class A {\n  fun x() {\n    y()\n  }\n}\n", dir.resolve("src/A.kt").readText())
    }

    @Test
    fun `init on missing directory fails cleanly`() {
        assertEquals(1, cli("", "init", "/definitely/not/there").code)
    }

    @Test
    fun `long lines are reported as warnings without failing`() {
        val dir = tempDir()
        dir.resolve(".vlformat").writeText("maxLineLength=20\n")
        dir.resolve("Long.kt").writeText("val itIsAVeryLongName = \"and an even longer string value\"\n")
        val run = cli("", "--check", dir.toString())
        assertEquals(0, run.code, "long lines alone must not fail --check")
        assertTrue(run.err.contains("LONG LINE"), run.err)
        assertTrue(run.err.contains("Long.kt:1"), run.err)
    }
}
