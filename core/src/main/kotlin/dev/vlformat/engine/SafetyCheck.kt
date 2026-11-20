package dev.vlformat.engine

import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Lexer
import dev.vlformat.lexer.Token
import dev.vlformat.lexer.TokenType
import dev.vlformat.rules.LineJoins

/**
 * Post-format safety net: a pure formatter must never change the code. Re-lexes
 * input and output and checks three things:
 *
 *  1. The significant token stream is identical (block-comment bodies are
 *     compared with per-line indentation normalized, since gutter realignment
 *     is legal).
 *  2. Line structure: no line break is added, and one is only removed where
 *     [LineJoins] allows it (attaching `{` or `else`/`catch`/`finally`/
 *     do-while `while`). Kotlin is newline sensitive, so this matters.
 *  3. Adjacency: tokens the lexer splits but the language reads as one
 *     (`>` `=` in `a>=b`, `>` `>` in `x>>2`, Kotlin `!in`, `as?`, labels) are
 *     never pulled apart, a Kotlin `@` never gains or loses its gap, and
 *     Kotlin `x!! in y` / `x!! == y` never lose theirs.
 *
 * What it cannot catch: anything the lexer itself gets wrong in BOTH input and
 * output (it is the same lexer). The corpus run in the README cross-checks
 * against the real Kotlin and Java parsers for that reason.
 *
 * Costs one extra lex of each side (~2x total pipeline cost).
 *
 * Note: token-REWRITING plugin rules (ones that intentionally change code or
 * comments) will trip this check by design — disable verification when using
 * such rules.
 */
object SafetyCheck {

    /** @return null when [formatted] is equivalent to [source], else a description. */
    fun verify(source: String, formatted: String, language: Language): String? {
        val beforeAll = Lexer(source, language).tokenize()
        val afterAll = Lexer(formatted, language).tokenize()
        val bIdx = significantIndices(beforeAll)
        val aIdx = significantIndices(afterAll)
        val before = IndexedView(beforeAll, bIdx)
        val after = IndexedView(afterAll, aIdx)
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
        for (i in 1 until before.size) {
            val left = before[i - 1]
            val right = before[i]
            val nlBefore = bIdx[i] - bIdx[i - 1] > 1
            val nlAfter = aIdx[i] - aIdx[i - 1] > 1
            if (nlAfter && !nlBefore) {
                return "line break inserted before '${clip(right.text)}' @ line ${right.line}"
            }
            if (nlBefore && !nlAfter &&
                !(right.type == TokenType.LBRACE && LineJoins.braceMayJoin(beforeAll, bIdx[i], language)) &&
                !LineJoins.clauseMayJoin(beforeAll, bIdx[i], language)
            ) {
                return "line break removed before '${clip(right.text)}' @ line ${right.line}"
            }
            val gluedBefore = !nlBefore && !right.hadSpaceBefore
            val gluedAfter = !nlAfter && !after[i].hadSpaceBefore
            if (gluedBefore && !gluedAfter && mustStayGlued(left, right, language)) {
                return "'${clip(left.text)}${clip(right.text)}' split apart @ line ${right.line}"
            }
            if (!gluedBefore && gluedAfter && mustStaySeparate(left, right, language)) {
                return "'${clip(left.text)} ${clip(right.text)}' joined @ line ${right.line}"
            }
        }
        return null
    }

    /** Pairs whose glued form means something else than the spaced form. */
    private fun mustStayGlued(l: Token, r: Token, language: Language): Boolean {
        if (l.type == TokenType.OPERATOR && l.text == ">" && r.type == TokenType.OPERATOR &&
            (r.text == ">" || r.text == "=")
        ) {
            return true // `>=`, `>>`, `>>=`, `>>>` are never lexed as one token
        }
        // Two glued word tokens: the lexer split one literal or name (should
        // not happen; if it does, never widen the split).
        if ((l.type == TokenType.IDENTIFIER || l.type == TokenType.NUMBER) &&
            (r.type == TokenType.IDENTIFIER || r.type == TokenType.NUMBER)
        ) {
            return true
        }
        return when (language) {
            Language.KOTLIN ->
                (l.text == "!" && r.type == TokenType.KEYWORD && (r.text == "in" || r.text == "is")) ||
                    (l.type == TokenType.KEYWORD && l.text == "as" && r.text == "?") ||
                    l.type == TokenType.AT || r.type == TokenType.AT
            Language.JAVA ->
                (l.text == "non" && r.text == "-") || (l.text == "-" && r.text == "sealed")
        }
    }

    /**
     * Kotlin `return @label` / `x @Ann` differ from `return@label` / `x@Ann`,
     * and `x!! in y` / `x!! == y` don't survive as `x!!in y` / `x!!== y`.
     */
    private fun mustStaySeparate(l: Token, r: Token, language: Language): Boolean {
        if (language != Language.KOTLIN) return false
        if (r.type == TokenType.AT) return true
        return l.type == TokenType.OPERATOR && l.text == "!!" &&
            (r.type == TokenType.KEYWORD || r.type == TokenType.IDENTIFIER ||
                (r.type == TokenType.OPERATOR && (r.text.startsWith("=") || r.text.startsWith("!"))))
    }

    /** The significant tokens of a full token list, without copying them. */
    private class IndexedView(private val all: List<Token>, private val idx: IntArray) : AbstractList<Token>() {
        override val size: Int get() = idx.size
        override fun get(index: Int): Token = all[idx[index]]
    }

    private fun significantIndices(tokens: List<Token>): IntArray {
        var n = 0
        for (t in tokens) if (t.type != TokenType.NEWLINE) n++
        val out = IntArray(n)
        var k = 0
        for (i in tokens.indices) if (tokens[i].type != TokenType.NEWLINE) out[k++] = i
        return out
    }

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
