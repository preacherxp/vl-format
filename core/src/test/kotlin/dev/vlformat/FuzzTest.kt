package dev.vlformat

import dev.vlformat.engine.Formatter
import dev.vlformat.engine.SafetyCheck
import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Lexer
import dev.vlformat.lexer.TokenType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic (seeded) fuzzing. Two generators:
 *
 * 1. Program generator — assembles random programs from statement templates.
 * 2. Whitespace mangler — re-lexes a program and rebuilds it with random
 *    spacing, line breaks, and indentation, always at token boundaries so the
 *    token stream itself is preserved.
 *
 * Invariants asserted for every sample:
 *  - formatting never throws
 *  - formatting is idempotent (fixed point after one pass)
 *  - output is token-equivalent to input ([SafetyCheck])
 *  - non-empty output ends with exactly one newline
 */
class FuzzTest {

    private val formatter = Formatter(discoverPlugins = false)

    private val kotlinTemplates = listOf(
        "val %N = %E",
        "var %N: Int = %E",
        "if (%C) { touch() }",
        "for (i in 1..%D) { use(i) }",
        "while (%C) { tick() }",
        "fun %N(p: Int): Int { return p + %D }",
        "fun %N() = list.map { it * %D }.filter { it > %D }.sum()",
        "val %N = obj?.field ?: %D",
        "val %N = \"pre \${a + %D} post\"",
        "try { f() } catch (e: Exception) { g() } finally { h() }",
        "val %N = when (k) { 1 -> %D else -> %D }",
        "class C%D(val x: Int) { fun d() = x * %D }",
        "val %N = arr[%D] + arr[%D]",
    )

    private val javaTemplates = listOf(
        "int %N = %E;",
        "if (%C) { touch(); }",
        "for (int i = 0; i < %D; i++) { use(i); }",
        "while (%C) { tick(); }",
        "static int %N(int p) { return p + %D; }",
        "String s%D = \"txt\" + name;",
        "try { f(); } catch (Exception e) { g(); } finally { h(); }",
        "long c%D = list.stream().map(x -> x * %D).count();",
        "int[] a%D = {%D, %D, %D};",
        "Object o%D = flag ? left : right;",
    )

    private val expressions = listOf("a + b", "x * %D", "f(a, b)", "-%D", "(a + %D) * b", "%D")
    private val conditions = listOf("a == b", "x != %D", "a && b || c", "!done", "n < %D")

    private fun instantiate(template: String, rnd: Random): String {
        var s = template
        while (s.contains("%E")) s = s.replaceFirst("%E", expressions[rnd.nextInt(expressions.size)])
        while (s.contains("%C")) s = s.replaceFirst("%C", conditions[rnd.nextInt(conditions.size)])
        while (s.contains("%N")) s = s.replaceFirst("%N", "v" + rnd.nextInt(100))
        while (s.contains("%D")) s = s.replaceFirst("%D", rnd.nextInt(1000).toString())
        return s
    }

    private fun generateProgram(rnd: Random, lang: Language): String {
        val templates = if (lang == Language.KOTLIN) kotlinTemplates else javaTemplates
        val body = (1..rnd.nextInt(3, 10)).joinToString("\n") { instantiate(templates[rnd.nextInt(templates.size)], rnd) }
        return if (lang == Language.KOTLIN) {
            "class Fuzz {\n$body\n}"
        } else {
            "class Fuzz {\nvoid all() {\n$body\n}\n}"
        }
    }

    /** Rebuild [src] with chaotic-but-boundary-safe whitespace. */
    private fun mangle(src: String, rnd: Random, lang: Language): String {
        val tokens = Lexer(src, lang).tokenize().filter { it.type != TokenType.NEWLINE }
        val sb = StringBuilder(src.length * 2)
        var prevText: String? = null
        for (t in tokens) {
            if (prevText != null) {
                val mustSpace = needsSpace(prevText, t.text)
                when {
                    rnd.nextInt(100) < 25 -> {
                        sb.append('\n')
                        repeat(rnd.nextInt(100) / 40) { sb.append('\n') } // occasional blank line
                        repeat(rnd.nextInt(9)) { sb.append(' ') } // random indent
                    }
                    mustSpace || rnd.nextInt(100) < 50 -> repeat(1 + rnd.nextInt(3)) { sb.append(' ') }
                    else -> {}
                }
            }
            sb.append(t.text)
            prevText = t.text
        }
        return sb.toString()
    }

    /** Would gluing [a] and [b] together fuse them into different tokens? */
    private fun needsSpace(a: String, b: String): Boolean {
        val aWord = a.last().isLetterOrDigit() || a.last() == '_'
        val bWord = b.first().isLetterOrDigit() || b.first() == '_'
        if (aWord && bWord) return true
        val aOp = a.last() in "+-*/%=<>!&|^?:.~"
        val bOp = b.first() in "+-*/%=<>!&|^?:.~"
        return aOp && bOp // conservative: never let operator chars touch
    }

    private fun checkInvariants(src: String, lang: Language, label: String) {
        val once = formatter.format(src, lang)
        val twice = formatter.format(once, lang)
        assertEquals(once, twice, "IDEMPOTENCY broke [$label]:\n$src")
        assertNull(SafetyCheck.verify(src, once, lang), "TOKENS changed [$label]:\n$src\n--- became ---\n$once")
        if (once.isNotEmpty()) {
            assertTrue(once.endsWith("\n") && !once.endsWith("\n\n"), "EOF newline wrong [$label]")
        }
    }

    @Test
    fun `250 random kotlin programs hold all invariants`() {
        val rnd = Random(42)
        repeat(250) { i ->
            checkInvariants(generateProgram(rnd, Language.KOTLIN), Language.KOTLIN, "kt-gen#$i")
        }
    }

    @Test
    fun `250 random java programs hold all invariants`() {
        val rnd = Random(43)
        repeat(250) { i ->
            checkInvariants(generateProgram(rnd, Language.JAVA), Language.JAVA, "java-gen#$i")
        }
    }

    @Test
    fun `150 whitespace-mangled kotlin programs hold all invariants`() {
        val rnd = Random(44)
        repeat(150) { i ->
            val program = generateProgram(rnd, Language.KOTLIN)
            checkInvariants(mangle(program, rnd, Language.KOTLIN), Language.KOTLIN, "kt-mangle#$i")
        }
    }

    @Test
    fun `150 whitespace-mangled java programs hold all invariants`() {
        val rnd = Random(45)
        repeat(150) { i ->
            val program = generateProgram(rnd, Language.JAVA)
            checkInvariants(mangle(program, rnd, Language.JAVA), Language.JAVA, "java-mangle#$i")
        }
    }

    @Test
    fun `mangled fixtures hold all invariants`() {
        val rnd = Random(46)
        val dir = java.nio.file.Paths.get(checkNotNull(javaClass.getResource("/fixtures")).toURI())
        java.nio.file.Files.walk(dir).use { stream ->
            for (path in stream.filter { it.fileName.toString().contains(".expected.") }.toList()) {
                val lang = checkNotNull(Language.fromFileName(path.fileName.toString()))
                val source = java.nio.file.Files.readString(path)
                repeat(20) { i ->
                    checkInvariants(mangle(source, rnd, lang), lang, "${path.fileName}#$i")
                }
            }
        }
    }
}
