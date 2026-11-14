package dev.vlformat

import dev.vlformat.engine.Formatter
import dev.vlformat.engine.SafetyCheck
import dev.vlformat.lexer.Language
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dogfood: vl-format must handle its own source tree — every core main-source
 * file formats without token changes and reaches a fixed point in one pass.
 */
class DogfoodTest {

    private val formatter = Formatter(discoverPlugins = false)

    private fun ownSources(): List<Path> {
        val root = Path.of("src/main/kotlin") // gradle test workingDir = module dir
        assertTrue(Files.isDirectory(root), "expected to run from the core module dir")
        return Files.walk(root).use { s ->
            s.filter { it.name.endsWith(".kt") }.sorted().toList()
        }
    }

    @Test
    fun `formats every own source file safely and idempotently`() {
        val files = ownSources()
        assertTrue(files.size >= 8, "expected the full core source tree, got ${files.size}")
        for (file in files) {
            val source = Files.readString(file)
            val once = formatter.format(source, Language.KOTLIN)
            val twice = formatter.format(once, Language.KOTLIN)
            assertEquals(once, twice, "not idempotent on own source: $file")
            assertNull(SafetyCheck.verify(source, once, Language.KOTLIN), "token change on own source: $file")
        }
    }
}
