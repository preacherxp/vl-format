package dev.vlformat.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Formatting options. Loaded from a `.vlformat` properties file found by
 * walking up from the formatted file's directory, or supplied programmatically.
 */
data class FormatConfig(
    /** Spaces per indent level. */
    val indentSize: Int = 4,
    /** Extra indent for continuation lines (wrapped expressions, open parens). */
    val continuationIndent: Int = 8,
    /** Maximum consecutive blank lines to keep. */
    val maxBlankLines: Int = 1,
    /**
     * Lines longer than this are REPORTED (stderr warnings) by the CLI.
     * vl-format never re-wraps lines; 0 disables the report.
     */
    val maxLineLength: Int = 0,
    /** Attach `{` to the previous line (K&R / Kotlin style). */
    val attachBraces: Boolean = true,
    /** Pull `else` / `catch` / `finally` up onto the `}` line. */
    val attachClauses: Boolean = true,
    /** Guarantee exactly one trailing newline at EOF. */
    val insertFinalNewline: Boolean = true,
    /** Realign `*` gutter lines inside block comments / KDoc / Javadoc. */
    val alignCommentStars: Boolean = true,
    /** Names of rules to disable, e.g. "blank-lines". */
    val disabledRules: Set<String> = emptySet(),
) {
    companion object {
        const val FILE_NAME = ".vlformat"

        fun fromProperties(props: Properties): FormatConfig {
            val defaults = FormatConfig()
            fun int(key: String, def: Int) = props.getProperty(key)?.trim()?.toIntOrNull() ?: def
            fun bool(key: String, def: Boolean) = props.getProperty(key)?.trim()?.toBooleanStrictOrNull() ?: def
            return FormatConfig(
                indentSize = int("indentSize", defaults.indentSize),
                continuationIndent = int("continuationIndent", defaults.continuationIndent),
                maxBlankLines = int("maxBlankLines", defaults.maxBlankLines),
                maxLineLength = int("maxLineLength", defaults.maxLineLength),
                attachBraces = bool("attachBraces", defaults.attachBraces),
                attachClauses = bool("attachClauses", defaults.attachClauses),
                insertFinalNewline = bool("insertFinalNewline", defaults.insertFinalNewline),
                alignCommentStars = bool("alignCommentStars", defaults.alignCommentStars),
                disabledRules = props.getProperty("disabledRules")
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                    ?: defaults.disabledRules,
            )
        }

        fun load(path: Path): FormatConfig {
            val props = Properties()
            Files.newBufferedReader(path).use { props.load(it) }
            return fromProperties(props)
        }

        /** Walk up from [startDir] looking for a `.vlformat` file. */
        fun discover(startDir: Path): FormatConfig {
            var dir: Path? = startDir.toAbsolutePath()
            while (dir != null) {
                val candidate = dir.resolve(FILE_NAME)
                if (Files.isRegularFile(candidate)) return load(candidate)
                dir = dir.parent
            }
            return FormatConfig()
        }
    }
}
