package dev.vlformat.lexer

/**
 * Token categories produced by [Lexer].
 *
 * Horizontal whitespace is never materialized as a token; it is recorded on the
 * following token via [Token.hadSpaceBefore]. Newlines ARE tokens so that rules
 * can reason about (and rewrite) line structure cheaply.
 */
enum class TokenType {
    NEWLINE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    STRING,
    CHAR,
    NUMBER,
    IDENTIFIER,
    KEYWORD,
    OPERATOR,
    AT,
    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    LBRACKET,
    RBRACKET,
    COMMA,
    SEMICOLON,
    /** Anything the lexer does not recognize; passed through verbatim. */
    UNKNOWN,
}

/**
 * A single lexical token.
 *
 * @property hadSpaceBefore true when the source had at least one space/tab
 *   directly before this token on the same line. Lets spacing rules make a
 *   KEEP decision (preserve author intent) for ambiguous pairs like generics.
 * @property line 1-based source line, for diagnostics.
 */
data class Token(
    val type: TokenType,
    val text: String,
    val hadSpaceBefore: Boolean,
    val line: Int,
) {
    val isComment: Boolean
        get() = type == TokenType.LINE_COMMENT || type == TokenType.BLOCK_COMMENT

    val isWordLike: Boolean
        get() = type == TokenType.IDENTIFIER || type == TokenType.KEYWORD ||
            type == TokenType.NUMBER || type == TokenType.STRING || type == TokenType.CHAR

    override fun toString(): String = "$type('${text.take(24)}')@$line"
}
