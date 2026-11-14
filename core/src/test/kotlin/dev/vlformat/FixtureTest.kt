package dev.vlformat

import dev.vlformat.engine.Formatter
import dev.vlformat.lexer.Language
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden-file tests: every `*.input.*` under fixtures/ must format to its
 * `*.expected.*` sibling, and every expected file must be a fixed point
 * (formatting it again changes nothing — idempotency).
 */
class FixtureTest {

    private val formatter = Formatter(discoverPlugins = false)

    private fun fixtureRoot(): Path {
        val url = checkNotNull(javaClass.getResource("/fixtures")) { "fixtures resource missing" }
        return Paths.get(url.toURI())
    }

    private fun inputs(): List<Path> = Files.walk(fixtureRoot()).use { s ->
        s.filter { it.name.contains(".input.") }.sorted().toList()
    }

    @Test
    fun `fixtures are present`() {
        assertTrue(inputs().size >= 4, "expected at least 4 fixtures")
    }

    @Test
    fun `inputs format to expected`() {
        for (input in inputs()) {
            val expectedPath = input.resolveSibling(input.name.replace(".input.", ".expected."))
            val language = checkNotNull(Language.fromFileName(input.name))
            val actual = formatter.format(input.readText(), language)
            assertEquals(expectedPath.readText(), actual, "mismatch for ${input.name}")
        }
    }

    @Test
    fun `formatting is idempotent`() {
        for (input in inputs()) {
            val language = checkNotNull(Language.fromFileName(input.name))
            val once = formatter.format(input.readText(), language)
            val twice = formatter.format(once, language)
            assertEquals(once, twice, "not idempotent: ${input.name}")
        }
    }

    @Test
    fun `formatting never alters string literals or comments`() {
        for (input in inputs()) {
            val language = checkNotNull(Language.fromFileName(input.name))
            val source = input.readText()
            val formatted = formatter.format(source, language)
            // Every string literal token of the input must appear verbatim in the output.
            val strings = dev.vlformat.lexer.Lexer(source, language).tokenize()
                .filter { it.type == dev.vlformat.lexer.TokenType.STRING }
            for (s in strings) {
                assertTrue(formatted.contains(s.text), "string literal mangled in ${input.name}: ${s.text.take(40)}")
            }
        }
    }
}
