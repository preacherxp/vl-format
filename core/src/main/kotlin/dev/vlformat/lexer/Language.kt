package dev.vlformat.lexer

enum class Language {
    JAVA,
    KOTLIN;

    companion object {
        /** Detect language from a file name, or null if unsupported. */
        fun fromFileName(name: String): Language? = when {
            name.endsWith(".java") -> JAVA
            name.endsWith(".kt") || name.endsWith(".kts") -> KOTLIN
            else -> null
        }
    }
}

/** Hard keywords per language (used for spacing decisions, not parsing). */
object Keywords {
    val JAVA: Set<String> = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "record", "return", "sealed",
        "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "var", "void", "volatile", "while",
        "yield", "permits", "non-sealed",
    )

    val KOTLIN: Set<String> = setOf(
        // hard keywords
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
        "if", "in", "interface", "is", "null", "object", "package", "return",
        "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
        "var", "when", "while",
        // soft keywords / modifiers that behave like keywords for spacing
        "by", "catch", "constructor", "delegate", "dynamic", "field", "finally",
        "get", "import", "init", "set", "where", "abstract", "actual", "annotation",
        "companion", "const", "crossinline", "data", "enum", "expect", "external",
        "final", "infix", "inline", "inner", "internal", "lateinit", "noinline",
        "open", "operator", "out", "override", "private", "protected", "public",
        "reified", "sealed", "suspend", "tailrec", "vararg",
    )

    /** Control-flow keywords that take a space before `(`: `if (x)`, not `if(x)`. */
    val CONTROL: Set<String> = setOf(
        "if", "for", "while", "when", "catch", "switch", "synchronized", "do",
        "return", "throw", "assert", "yield", "case",
    )

    fun forLanguage(language: Language): Set<String> = when (language) {
        Language.JAVA -> JAVA
        Language.KOTLIN -> KOTLIN
    }
}
