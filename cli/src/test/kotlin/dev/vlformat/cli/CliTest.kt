package dev.vlformat.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {

    private class Run(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(arrayOf(*args), PrintStream(out, true), PrintStream(err, true))
        return Run(code, out.toString(), err.toString())
    }

    private fun tempProject(): Path {
        val dir = Files.createTempDirectory("vlfmt-cli")
        dir.resolve("src").createDirectories()
        dir.resolve("src/A.kt").writeText("fun a(  ):Int{return 1+2}\n")
        dir.resolve("src/B.java").writeText("class B{int b(){return 1+2;}}\n")
        dir.resolve("src/notes.txt").writeText("not source\n")
        dir.resolve("build").createDirectories()
        dir.resolve("build/Skip.kt").writeText("fun skip( ):Int{return 0}\n")
        return dir
    }

    @Test
    fun `check mode reports and exits 2 without writing`() {
        val dir = tempProject()
        val before = dir.resolve("src/A.kt").readText()
        val run = cli("--check", dir.toString())
        assertEquals(2, run.code, run.err)
        assertTrue(run.out.contains("NEEDS FORMAT"))
        assertTrue(run.out.contains("A.kt"))
        assertTrue(!run.out.contains("Skip.kt"), "build/ must be skipped")
        assertEquals(before, dir.resolve("src/A.kt").readText(), "check must not write")
    }

    @Test
    fun `format writes files and second check is clean`() {
        val dir = tempProject()
        assertEquals(0, cli(dir.toString()).code)
        assertEquals("fun a(): Int { return 1 + 2 }\n", dir.resolve("src/A.kt").readText())
        assertEquals("class B { int b() { return 1 + 2; } }\n", dir.resolve("src/B.java").readText())
        assertEquals(0, cli("--check", dir.toString()).code)
    }

    @Test
    fun `stdout mode prints formatted content`() {
        val dir = tempProject()
        val run = cli("--stdout", dir.resolve("src/A.kt").toString())
        assertEquals(0, run.code)
        assertEquals("fun a(): Int { return 1 + 2 }\n", run.out)
    }

    @Test
    fun `dry run lists but does not write`() {
        val dir = tempProject()
        val before = dir.resolve("src/A.kt").readText()
        val run = cli("--dry-run", dir.toString())
        assertEquals(0, run.code)
        assertTrue(run.out.contains("WOULD FORMAT"))
        assertEquals(before, dir.resolve("src/A.kt").readText())
    }

    @Test
    fun `explicit config file is applied`() {
        val dir = tempProject()
        val cfg = dir.resolve("style.properties")
        cfg.writeText("indentSize=2\n")
        dir.resolve("src/C.kt").writeText("class C {\nfun x() {\ny()\n}\n}\n")
        assertEquals(0, cli("--config", cfg.toString(), dir.resolve("src/C.kt").toString()).code)
        assertEquals("class C {\n  fun x() {\n    y()\n  }\n}\n", dir.resolve("src/C.kt").readText())
    }

    @Test
    fun `discovered vlformat config is applied`() {
        val dir = tempProject()
        dir.resolve(".vlformat").writeText("indentSize=2\n")
        assertEquals(0, cli(dir.toString()).code)
        assertEquals("class B { int b() { return 1 + 2; } }\n", dir.resolve("src/B.java").readText())
        dir.resolve("src/D.kt").writeText("class D {\nfun x() {}\n}\n")
        assertEquals(0, cli(dir.toString()).code)
        assertEquals("class D {\n  fun x() {}\n}\n", dir.resolve("src/D.kt").readText())
    }

    @Test
    fun `bad usage returns 1 with message`() {
        assertEquals(1, cli().code)
        assertEquals(1, cli("--bogus").code)
        assertEquals(1, cli("/definitely/not/there.kt").code)
        assertTrue(cli("--bogus").err.contains("unknown option"))
    }

    @Test
    fun `project inside a directory named like a skipped one is still walked`() {
        // Skip names apply below the given root only, not to its own path.
        val outer = Files.createTempDirectory("vlfmt-cli").resolve("build/proj")
        outer.resolve("src").createDirectories()
        outer.resolve("src/A.kt").writeText("fun a( )=1\n")
        outer.resolve("build").createDirectories()
        outer.resolve("build/Gen.kt").writeText("fun g( )=1\n")
        val run = cli("--check", outer.toString())
        assertEquals(2, run.code, run.err)
        assertTrue(run.out.contains("A.kt"))
        assertTrue(!run.out.contains("Gen.kt"), "nested build/ is still skipped")
    }

    @Test
    fun `errors are reported on stderr in stdout mode too`() {
        val dir = tempProject()
        val bad = dir.resolve("src/Bad.java")
        Files.write(bad, byteArrayOf(0xff.toByte(), 0xfe.toByte(), 'x'.code.toByte()))
        val run = cli("--stdout", bad.toString())
        assertEquals(1, run.code)
        assertTrue(run.err.contains("Bad.java") && run.err.contains("UTF-8"), run.err)
    }

    @Test
    fun `check mode exit codes for CI`() {
        val dir = tempProject()
        assertEquals(2, cli("--check", dir.toString()).code)
        assertEquals(0, cli(dir.toString()).code)
        val clean = cli("--check", dir.toString())
        assertEquals(0, clean.code)
        assertEquals("", clean.out)
    }

    @Test
    fun `help and version exit 0`() {
        assertEquals(0, cli("--help").code)
        assertEquals(0, cli("--version").code)
        assertTrue(cli("--version").out.contains("vl-format"))
    }
}
