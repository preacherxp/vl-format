package dev.vlformat.rules

import dev.vlformat.lexer.Language
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
 * plausibly own the brace (`;`, `{`, `}`, or nothing). Kotlin is newline
 * sensitive (`foo()` + newline + `{ }` is a call followed by a lambda, not a
 * trailing lambda), so there it only attaches where [LineJoins] can show the
 * brace opens a declaration body or a control-flow block.
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
                if (j < i - 1 && LineJoins.braceMayJoin(tokens, i, ctx.language)) {
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

    override fun apply(tokens: MutableList<Token>, ctx: RuleContext) {
        if (!ctx.config.attachClauses) return
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TokenType.KEYWORD && t.text in LineJoins.CLAUSE_KEYWORDS) {
                var j = i - 1
                while (j >= 0 && tokens[j].type == TokenType.NEWLINE) j--
                if (j < i - 1 && LineJoins.clauseMayJoin(tokens, i, ctx.language)) {
                    val kw = t.copy(hadSpaceBefore = true)
                    tokens.subList(j + 1, i).clear()
                    tokens[j + 1] = kw
                    i = j + 1
                }
            }
            i++
        }
    }
}

/**
 * The only places where vl-format may remove a line break. Used by the
 * built-in rules to decide, and by [dev.vlformat.engine.SafetyCheck] to
 * verify any rule (built-in or plugin) stayed inside these bounds.
 *
 * All functions take the full token list (NEWLINE tokens included) and the
 * index of the token that would be pulled up onto the previous line.
 */
object LineJoins {
    val CLAUSE_KEYWORDS = setOf("else", "catch", "finally", "while")

    /** Keywords whose `{` on the next line is unambiguously their block. */
    private val KOTLIN_BLOCK_KEYWORDS = setOf("else", "try", "finally", "do", "init", "object", "when")

    /** `kw (...)` + newline + `{`: the brace is the keyword's block. */
    private val KOTLIN_PAREN_KEYWORDS = setOf("if", "for", "while", "when", "catch", "constructor", "get", "set", "fun")

    /** A line containing one of these declares something that owns a body. */
    private val KOTLIN_DECLARATION_KEYWORDS = setOf("fun", "class", "interface", "object", "constructor", "init", "get", "set")

    /** May the `{` at [braceIndex] be pulled up onto the previous line? */
    fun braceMayJoin(tokens: List<Token>, braceIndex: Int, language: Language): Boolean {
        val j = prevSignificant(tokens, braceIndex)
        if (j < 0) return false
        val prev = tokens[j]
        if (prev.isComment || prev.type == TokenType.SEMICOLON ||
            prev.type == TokenType.LBRACE || prev.type == TokenType.RBRACE
        ) {
            return false
        }
        if (language != Language.KOTLIN) return true
        return when (prev.type) {
            TokenType.KEYWORD -> prev.text in KOTLIN_BLOCK_KEYWORDS
            // Inside an argument list or after a binary operator the line
            // continues anyway: `f(a,` / `x =` / `->` + newline + `{`.
            TokenType.COMMA, TokenType.LPAREN, TokenType.LBRACKET -> true
            TokenType.OPERATOR -> when (prev.text) {
                ">", "?" -> isDeclarationLine(tokens, j)
                "!!", "++", "--" -> false
                else -> true
            }
            TokenType.RPAREN -> {
                val open = matchingOpen(tokens, j)
                val owner = if (open > 0) tokens.getOrNull(prevSignificant(tokens, open)) else null
                (owner != null && owner.type == TokenType.KEYWORD && owner.text in KOTLIN_PAREN_KEYWORDS) ||
                    isDeclarationLine(tokens, j)
            }
            TokenType.IDENTIFIER -> isDeclarationLine(tokens, j)
            else -> false
        }
    }

    /** May the clause keyword at [kwIndex] be pulled up onto the `}` line? */
    fun clauseMayJoin(tokens: List<Token>, kwIndex: Int, language: Language): Boolean {
        val t = tokens[kwIndex]
        if (t.type != TokenType.KEYWORD || t.text !in CLAUSE_KEYWORDS) return false
        val j = prevSignificant(tokens, kwIndex)
        if (j < 0 || tokens[j].type != TokenType.RBRACE) return false
        val next = tokens.getOrNull(nextSignificant(tokens, kwIndex))
        return when (t.text) {
            // A Kotlin `when` branch (`else -> ...`) is not an if-else clause.
            "else" -> !(next != null && next.type == TokenType.OPERATOR && next.text == "->")
            // Kotlin's `catch`/`finally` are soft keywords: `catch<T> { }` or
            // `finally { }` on its own line can be a call (e.g. Flow.catch), so
            // the `}` must close a `try { }` or `catch (...) { }` block.
            "catch" -> language != Language.KOTLIN ||
                (next?.type == TokenType.LPAREN && closesTryOrCatch(tokens, j))
            "finally" -> language != Language.KOTLIN ||
                (next?.type == TokenType.LBRACE && closesTryOrCatch(tokens, j))
            // Only do-while's `while`; a plain loop after a block stays put.
            "while" -> isDoWhile(tokens, j)
            else -> false
        }
    }

    /**
     * Scan back over the logical line ending at [index] (line breaks inside
     * parentheses don't end it). True when it declares something with a body
     * (`fun`, `class`, ...) and no `=` or `by` follows the declaring keyword,
     * so a `{` after it can only be that body.
     */
    private fun isDeclarationLine(tokens: List<Token>, index: Int): Boolean {
        var depth = 0
        var sawAssign = false
        var k = index
        while (k >= 0) {
            val t = tokens[k]
            when (t.type) {
                TokenType.RPAREN, TokenType.RBRACKET -> depth++
                TokenType.LPAREN, TokenType.LBRACKET -> if (--depth < 0) return false
                TokenType.NEWLINE, TokenType.SEMICOLON, TokenType.LBRACE, TokenType.RBRACE ->
                    if (depth == 0) return false
                TokenType.OPERATOR -> if (depth == 0 && t.text == "=") sawAssign = true
                TokenType.KEYWORD -> if (depth == 0) {
                    if (t.text == "by") sawAssign = true
                    if (t.text in KOTLIN_DECLARATION_KEYWORDS) return !sawAssign
                }
                else -> {}
            }
            k--
        }
        return false
    }

    /** Does the `}` at [rbraceIndex] close a `try { }` or `catch (...) { }` block? */
    private fun closesTryOrCatch(tokens: List<Token>, rbraceIndex: Int): Boolean {
        var depth = 0
        var open = -1
        for (k in rbraceIndex downTo 0) {
            val type = tokens[k].type
            if (type == TokenType.RBRACE) depth++
            if (type == TokenType.LBRACE && --depth == 0) {
                open = k
                break
            }
        }
        if (open < 0) return false
        val before = tokens.getOrNull(prevSignificant(tokens, open)) ?: return false
        if (before.type == TokenType.KEYWORD) return before.text == "try"
        if (before.type != TokenType.RPAREN) return false
        val paren = matchingOpen(tokens, prevSignificant(tokens, open))
        val owner = tokens.getOrNull(prevSignificant(tokens, paren)) ?: return false
        return paren > 0 && owner.type == TokenType.KEYWORD && owner.text == "catch"
    }

    private fun matchingOpen(tokens: List<Token>, closeIndex: Int): Int {
        var depth = 0
        for (k in closeIndex downTo 0) {
            when (tokens[k].type) {
                TokenType.RPAREN -> depth++
                TokenType.LPAREN -> if (--depth == 0) return k
                else -> {}
            }
        }
        return -1
    }

    private fun prevSignificant(tokens: List<Token>, from: Int): Int {
        var k = from - 1
        while (k >= 0 && tokens[k].type == TokenType.NEWLINE) k--
        return k
    }

    private fun nextSignificant(tokens: List<Token>, from: Int): Int {
        var k = from + 1
        while (k < tokens.size && tokens[k].type == TokenType.NEWLINE) k++
        return k
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
