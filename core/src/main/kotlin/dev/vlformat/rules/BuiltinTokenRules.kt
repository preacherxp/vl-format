package dev.vlformat.rules

import dev.vlformat.lexer.Token
import dev.vlformat.lexer.TokenType

/**
 * Collapses runs of blank lines to `maxBlankLines` and strips blank lines at
 * the start of the file and directly after `{` / before `}`.
 */
class BlankLineRule : TokenRule {
    override val name = "blank-lines"
    override val order = 10

    override fun apply(tokens: MutableList<Token>, ctx: RuleContext) {
        val maxNewlines = ctx.config.maxBlankLines + 1
        val out = ArrayList<Token>(tokens.size)
        var newlineRun = 0
        for (token in tokens) {
            if (token.type == TokenType.NEWLINE) {
                newlineRun++
                continue
            }
            if (newlineRun > 0) {
                var keep = minOf(newlineRun, maxNewlines)
                // No newlines at all before the first token; no blank line
                // right after an opening brace.
                val prev = out.lastOrNull()
                if (prev == null) keep = 0
                if (prev?.type == TokenType.LBRACE) keep = minOf(keep, 1)
                // No blank lines directly before a closing brace.
                if (token.type == TokenType.RBRACE) keep = minOf(keep, 1)
                repeat(keep) { out.add(Token(TokenType.NEWLINE, "\n", false, token.line)) }
                newlineRun = 0
            }
            out.add(token)
        }
        // Trailing newlines are normalized by the renderer; drop them here.
        tokens.clear()
        tokens.addAll(out)
    }
}

/**
 * Moves an opening `{` up onto the previous line (K&R / Kotlin style):
 *
 * ```
 * if (x)          if (x) {
 * {          →        ...
 * ```
 *
 * Skips when a comment sits between, or when the previous token can't
 * plausibly own the brace (`;`, `{`, `}`, or nothing).
 */
class AttachBraceRule : TokenRule {
    override val name = "attach-braces"
    override val order = 20

    override fun apply(tokens: MutableList<Token>, ctx: RuleContext) {
        if (!ctx.config.attachBraces) return
        var i = 0
        while (i < tokens.size) {
            if (tokens[i].type == TokenType.LBRACE) {
                var j = i - 1
                while (j >= 0 && tokens[j].type == TokenType.NEWLINE) j--
                val prev = tokens.getOrNull(j)
                val attachable = prev != null && j < i - 1 && !prev.isComment &&
                    prev.type != TokenType.SEMICOLON &&
                    prev.type != TokenType.LBRACE &&
                    prev.type != TokenType.RBRACE
                if (attachable) {
                    // Remove the newlines between prev and `{`.
                    val brace = tokens[i]
                    tokens.subList(j + 1, i).clear()
                    tokens[j + 1] = brace.copy(hadSpaceBefore = true)
                    i = j + 1
                }
            }
            i++
        }
    }
}

/**
 * Pulls trailing clauses up onto the closing brace line:
 *
 * ```
 * }              } else {
 * else {    →
 * ```
 *
 * Applies to `else`, `catch`, `finally`, and do-while's `while`.
 */
class AttachClauseRule : TokenRule {
    override val name = "attach-clauses"
    override val order = 21

    private val clauseKeywords = setOf("else", "catch", "finally", "while")

    override fun apply(tokens: MutableList<Token>, ctx: RuleContext) {
        if (!ctx.config.attachClauses) return
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TokenType.KEYWORD && t.text in clauseKeywords) {
                var j = i - 1
                while (j >= 0 && tokens[j].type == TokenType.NEWLINE) j--
                if (j < i - 1 && j >= 0 && tokens[j].type == TokenType.RBRACE) {
                    // `while` only attaches for do-while; a plain `while` loop after
                    // a block must stay on its own line. Heuristic: the matching
                    // `{` must be preceded by `do`. A Kotlin `when` branch
                    // (`else -> ...`) is not an if-else clause — leave it alone.
                    if ((t.text != "while" || isDoWhile(tokens, j)) &&
                        !(t.text == "else" && isWhenBranch(tokens, i))
                    ) {
                        val kw = t.copy(hadSpaceBefore = true)
                        tokens.subList(j + 1, i).clear()
                        tokens[j + 1] = kw
                        i = j + 1
                    }
                }
            }
            i++
        }
    }

    private fun isWhenBranch(tokens: List<Token>, elseIndex: Int): Boolean {
        var k = elseIndex + 1
        while (k < tokens.size && tokens[k].type == TokenType.NEWLINE) k++
        val next = tokens.getOrNull(k)
        return next != null && next.type == TokenType.OPERATOR && next.text == "->"
    }

    private fun isDoWhile(tokens: List<Token>, rbraceIndex: Int): Boolean {
        var depth = 0
        for (k in rbraceIndex downTo 0) {
            when (tokens[k].type) {
                TokenType.RBRACE -> depth++
                TokenType.LBRACE -> {
                    depth--
                    if (depth == 0) {
                        var p = k - 1
                        while (p >= 0 && tokens[p].type == TokenType.NEWLINE) p--
                        return p >= 0 && tokens[p].type == TokenType.KEYWORD && tokens[p].text == "do"
                    }
                }
                else -> {}
            }
        }
        return false
    }
}
