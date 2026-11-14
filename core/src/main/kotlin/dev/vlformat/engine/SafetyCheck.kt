package dev.vlformat.engine

import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Lexer
import dev.vlformat.lexer.Token
import dev.vlformat.lexer.TokenType

/**
 * Post-format semantic safety net: a pure formatter must never change the
 * significant token stream. Re-lexes input and output and compares tokens
 * (whitespace and line structure excluded; block-comment bodies compared with
 * per-line indentation normalized, since gutter realignment is legal).
 *
 * Costs one extra lex of each side (~2x total pipeline cost) and catches any
 * corruption bug in rules or renderer before it reaches disk.
 *
 * Note: token-REWRITING plugin rules (ones that intentionally change code or
 * comments) will trip this check by design — disable verification when using
 * such rules.
 */
object SafetyCheck {

    /** @return null when [formatted] is token-equivalent to [source], else a description. */
    fun verify(source: String, formatted: String, language: Language): String? {
        val before = significant(Lexer(source, language).tokenize())
        val after = significant(Lexer(formatted, language).tokenize())
        if (before.size != after.size) {
            return "token count changed: ${before.size} -> ${after.size} " +
                "(near ${firstDivergence(before, after)})"
        }
        for (i in before.indices) {
            val b = before[i]
            val a = after[i]
            if (b.type != a.type || !textEquivalent(b, a)) {
                return "token #$i changed: ${b.type}('${clip(b.text)}') @ line ${b.line} " +
                    "-> ${a.type}('${clip(a.text)}') @ line ${a.line}"
            }
        }
        return null
    }

    private fun significant(tokens: List<Token>): List<Token> =
        tokens.filter { it.type != TokenType.NEWLINE }

    private fun textEquivalent(b: Token, a: Token): Boolean {
        if (b.text == a.text) return true
        if (b.type == TokenType.BLOCK_COMMENT) {
            return normalizeComment(b.text) == normalizeComment(a.text)
        }
        return false
    }

    private fun normalizeComment(text: String): String =
        text.lineSequence().joinToString("\n") { it.trim() }.trimEnd()

    private fun firstDivergence(before: List<Token>, after: List<Token>): String {
        val n = minOf(before.size, after.size)
        for (i in 0 until n) {
            if (before[i].type != after[i].type || !textEquivalent(before[i], after[i])) {
                return "token #$i, input line ${before[i].line}: '${clip(before[i].text)}'"
            }
        }
        return "end of shorter stream (token #$n)"
    }

    private fun clip(s: String): String = if (s.length > 32) s.take(32) + "…" else s
}
