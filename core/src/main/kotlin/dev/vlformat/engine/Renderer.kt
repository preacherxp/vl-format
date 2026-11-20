package dev.vlformat.engine

import dev.vlformat.config.FormatConfig
import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Lexer
import dev.vlformat.lexer.Token
import dev.vlformat.lexer.TokenType
import dev.vlformat.rules.RuleContext
import dev.vlformat.rules.SpaceDecision
import dev.vlformat.rules.SpacingRule

/**
 * Turns a rule-processed token stream back into text: computes indentation
 * and applies the spacing rule chain between adjacent tokens.
 *
 * Indentation uses one stack of frames covering both `{}` blocks and
 * `()`/`[]` groups. Every frame stores absolute widths anchored to the line
 * that OPENED it (not a global depth counter), so constructs opened on
 * continuation lines keep their contents to the right of the opener:
 *
 * ```
 * fun user(name: String) =
 *         apply {                 // continuation line, width 8
 *             this.name = name    // 8 + indentSize
 *         }                       // closes at 8
 *
 * val v =
 *         canonicalize(           // continuation line, width 8
 *                 encoded = true, // 8 + continuationIndent
 *         )                       // closes at 8
 * ```
 *
 * A line after one that opens a body without a brace (`if (x)`, `else`,
 * a `->` branch) gets one indent level, and so do Kotlin `get`/`set`
 * accessor lines under a property.
 *
 * Single pass, single StringBuilder sized to the input — this is the hot path.
 */
class Renderer(private val spacingRules: List<SpacingRule>) {

    private enum class FrameKind { BRACE, GROUP }

    private enum class BlockKind { NORMAL, SWITCH }

    /** Kotlin line classification for accessor indentation. */
    private enum class LineKind { OTHER, PROPERTY, ACCESSOR }

    /** One open `{`, `(` or `[`: body indent width and closer alignment. */
    private class Frame(
        val kind: FrameKind,
        val bodyIndent: Int,
        val closeIndent: Int,
        val blockKind: BlockKind = BlockKind.NORMAL,
        /** A class/object/interface body: declarations only, so `set(...)` is an accessor. */
        val declarations: Boolean = false,
        /** Opened on a Kotlin accessor line (`get() {`). */
        val accessor: Boolean = false,
    )

    fun render(tokens: List<Token>, ctx: RuleContext, sizeHint: Int): String {
        val config = ctx.config
        val sb = StringBuilder(sizeHint + 64)
        val frames = ArrayList<Frame>()

        var atLineStart = true
        var prevIndex = -1 // last significant (non-newline) token emitted
        var prevLineEndedContinuator = false
        var sawSwitchOnLine = false
        var lineIndent = 0
        var indentStr = ""
        // Indent of the opener line of the last ()/[] group CLOSED on the
        // current line. A `{` after it anchors there, so `if (a &&\n b) {`
        // bodies indent from the `if` line, not the continuation line.
        var closedGroupIndentOnLine = -1
        // Indent for the next line when the current one opens a brace-less body.
        var braceLessBodyIndent = -1
        var framesAtLineStart = 0
        var lineKind = LineKind.OTHER
        var prevLineKind = LineKind.OTHER
        val kotlin = ctx.language == Language.KOTLIN

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TokenType.NEWLINE) {
                prevLineEndedContinuator = if (!atLineStart && prevIndex >= 0) {
                    isEolContinuator(tokens, prevIndex)
                } else if (atLineStart) {
                    // Blank line: reset so the next line isn't a continuation.
                    false
                } else {
                    prevLineEndedContinuator
                }
                if (!atLineStart && prevIndex >= 0) {
                    braceLessBodyIndent = braceLessBody(tokens, prevIndex, frames, framesAtLineStart, lineIndent, closedGroupIndentOnLine, config)
                    prevLineKind = lineKind
                } else if (atLineStart) {
                    braceLessBodyIndent = -1
                    prevLineKind = LineKind.OTHER
                }
                sb.append('\n')
                atLineStart = true
                sawSwitchOnLine = false
                i++
                continue
            }

            if (atLineStart) {
                lineIndent = indentWidthFor(t, frames, prevLineEndedContinuator, config)
                if (braceLessBodyIndent >= 0 && !isCloser(t) && t.type != TokenType.LBRACE) {
                    lineIndent = braceLessBodyIndent
                }
                lineKind = if (kotlin) classifyLine(tokens, i, frames) else LineKind.OTHER
                if (lineKind == LineKind.ACCESSOR) {
                    if ((prevLineKind == LineKind.PROPERTY || prevLineKind == LineKind.ACCESSOR) &&
                        frames.lastOrNull().let { it == null || it.declarations }
                    ) {
                        lineIndent = (frames.lastOrNull()?.bodyIndent ?: 0) + config.indentSize
                    } else {
                        lineKind = LineKind.OTHER
                    }
                }
                framesAtLineStart = frames.size
                indentStr = " ".repeat(lineIndent)
                sb.append(indentStr)
                atLineStart = false
                closedGroupIndentOnLine = -1
            } else if (prevIndex >= 0) {
                val prev = tokens[prevIndex]
                val space = when (decideSpace(tokens, prevIndex, i, ctx)) {
                    SpaceDecision.ONE -> true
                    SpaceDecision.KEEP -> t.hadSpaceBefore
                    else -> false
                }
                // Never let two operator tokens fuse into a longer one
                // (`..` + `<` re-lexing as `..<` would corrupt the stream).
                if (space ||
                    (prev.type == TokenType.OPERATOR && t.type == TokenType.OPERATOR &&
                        Lexer.wouldFuse(prev.text, t.text))
                ) {
                    sb.append(' ')
                }
            }

            emitToken(sb, t, indentStr, ctx)

            when (t.type) {
                TokenType.LBRACE -> {
                    val blockKind = if (sawSwitchOnLine) BlockKind.SWITCH else BlockKind.NORMAL
                    val anchor = if (closedGroupIndentOnLine >= 0) closedGroupIndentOnLine else lineIndent
                    frames.add(
                        Frame(
                            FrameKind.BRACE, anchor + config.indentSize, anchor, blockKind,
                            declarations = kotlin && opensDeclarationBody(tokens, i),
                            accessor = lineKind == LineKind.ACCESSOR,
                        ),
                    )
                    sawSwitchOnLine = false
                }
                TokenType.LPAREN, TokenType.LBRACKET ->
                    frames.add(Frame(FrameKind.GROUP, lineIndent + config.continuationIndent, lineIndent))
                TokenType.RBRACE -> popMatching(frames, FrameKind.BRACE)
                TokenType.RPAREN, TokenType.RBRACKET ->
                    popMatching(frames, FrameKind.GROUP)?.let { closedGroupIndentOnLine = it.closeIndent }
                TokenType.KEYWORD -> if (t.text == "switch") sawSwitchOnLine = true
                else -> {}
            }
            prevIndex = i
            i++
        }

        // Normalize EOF: strip trailing whitespace/newlines, then one final
        // newline — unless the file ends in an unterminated string, where a
        // newline would be absorbed INTO the string token on re-lex.
        var end = sb.length
        while (end > 0 && (sb[end - 1] == '\n' || sb[end - 1] == ' ' || sb[end - 1] == '\t')) end--
        sb.setLength(end)
        val lastToken = if (prevIndex >= 0) tokens[prevIndex] else null
        if (config.insertFinalNewline && sb.isNotEmpty() && !isUnterminatedString(lastToken)) {
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Indent for the line after [lastIndex] when that line opens a body
     * without a brace: `if (x)` / `for (...)` / `while (...)`, a trailing
     * `else`, or a `->` branch. -1 when it doesn't. Only at statement level
     * (inside `{}` or top level) and not for `{ x ->` lambda parameters.
     */
    private fun braceLessBody(
        tokens: List<Token>,
        lastIndex: Int,
        frames: List<Frame>,
        framesAtLineStart: Int,
        lineIndent: Int,
        closedGroupIndent: Int,
        config: FormatConfig,
    ): Int {
        val top = frames.lastOrNull()
        if (top != null && top.kind != FrameKind.BRACE) return -1
        if (frames.size > framesAtLineStart) return -1
        val last = tokens[lastIndex]
        val opens = when (last.type) {
            TokenType.OPERATOR -> last.text == "->"
            TokenType.KEYWORD -> last.text == "else"
            TokenType.RPAREN -> isControlParen(tokens, lastIndex)
            else -> false
        }
        if (!opens) return -1
        val anchor = if (last.type == TokenType.RPAREN && closedGroupIndent >= 0) closedGroupIndent else lineIndent
        return anchor + config.indentSize
    }

    /** `)` closing `if (...)`, `for (...)` or `while (...)` (not do-while's `} while (...)`). */
    private fun isControlParen(tokens: List<Token>, rparenIndex: Int): Boolean {
        var depth = 0
        var k = rparenIndex
        while (k >= 0) {
            val type = tokens[k].type
            if (type == TokenType.RPAREN) depth++
            if (type == TokenType.LPAREN && --depth == 0) break
            k--
        }
        val owner = prevSignificantIndex(tokens, k)
        if (owner < 0) return false
        val o = tokens[owner]
        if (o.type != TokenType.KEYWORD || o.text !in BRACELESS_CONTROL) return false
        val before = prevSignificantIndex(tokens, owner)
        return !(o.text == "while" && before >= 0 && tokens[before].type == TokenType.RBRACE)
    }

    /** Does the `{` at [braceIndex] open a class, object or interface body? */
    private fun opensDeclarationBody(tokens: List<Token>, braceIndex: Int): Boolean {
        var depth = 0
        var k = braceIndex - 1
        var steps = 0
        while (k >= 0 && steps < 256) {
            val t = tokens[k]
            when (t.type) {
                TokenType.RPAREN, TokenType.RBRACKET -> depth++
                TokenType.LPAREN, TokenType.LBRACKET -> if (--depth < 0) return false
                TokenType.LBRACE, TokenType.RBRACE, TokenType.SEMICOLON -> if (depth == 0) return false
                TokenType.OPERATOR -> if (depth == 0 && t.text == "=") return false
                TokenType.KEYWORD -> if (depth == 0) {
                    if (t.text == "class" || t.text == "object" || t.text == "interface") return true
                    if (t.text == "fun") return false
                }
                else -> {}
            }
            k--
            steps++
        }
        return false
    }

    /**
     * Kotlin: is the line starting at [start] a property declaration, an
     * accessor (`get() = ...`, `private set`), or a `}` closing an accessor body?
     */
    private fun classifyLine(tokens: List<Token>, start: Int, frames: List<Frame>): LineKind {
        val first = tokens[start]
        if (first.type == TokenType.RBRACE) {
            return if (frames.lastOrNull()?.accessor == true) LineKind.ACCESSOR else LineKind.OTHER
        }
        var k = start
        while (k < tokens.size) {
            val t = tokens[k]
            when {
                t.type == TokenType.AT -> {
                    // Skip `@Ann`, `@a.b.Ann`, `@Ann(...)`.
                    k++
                    while (k < tokens.size && (tokens[k].type == TokenType.IDENTIFIER || tokens[k].type == TokenType.KEYWORD ||
                            tokens[k].text == "." || tokens[k].text == ":")
                    ) {
                        k++
                    }
                    if (k < tokens.size && tokens[k].type == TokenType.LPAREN) {
                        var depth = 0
                        while (k < tokens.size) {
                            if (tokens[k].type == TokenType.LPAREN) depth++
                            if (tokens[k].type == TokenType.RPAREN && --depth == 0) break
                            if (tokens[k].type == TokenType.NEWLINE) return LineKind.OTHER
                            k++
                        }
                        k++
                    }
                    continue
                }
                t.type != TokenType.KEYWORD -> return LineKind.OTHER
                t.text == "val" || t.text == "var" -> return LineKind.PROPERTY
                t.text == "get" || t.text == "set" -> {
                    val next = tokens.getOrNull(k + 1)
                    return if (next == null || next.type == TokenType.NEWLINE || next.isComment ||
                        next.type == TokenType.LPAREN || next.type == TokenType.LBRACE ||
                        (next.type == TokenType.OPERATOR && next.text == "=")
                    ) {
                        LineKind.ACCESSOR
                    } else {
                        LineKind.OTHER
                    }
                }
                t.text in ACCESSOR_MODIFIERS -> k++
                else -> return LineKind.OTHER
            }
        }
        return LineKind.OTHER
    }

    private fun prevSignificantIndex(tokens: List<Token>, from: Int): Int {
        var k = from - 1
        while (k >= 0 && tokens[k].type == TokenType.NEWLINE) k--
        return k
    }

    private fun isUnterminatedString(t: Token?): Boolean {
        if (t == null || t.type != TokenType.STRING) return false
        val text = t.text
        if (text.startsWith("\"\"\"")) return text.length < 6 || !text.endsWith("\"\"\"")
        if (text.length < 2 || !text.endsWith("\"")) return true
        // A closing quote preceded by an odd number of backslashes is escaped.
        var backslashes = 0
        var i = text.length - 2
        while (i >= 0 && text[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    private fun decideSpace(tokens: List<Token>, left: Int, right: Int, ctx: RuleContext): SpaceDecision {
        for (rule in spacingRules) {
            val d = rule.spaceBetween(tokens, left, right, ctx)
            if (d != SpaceDecision.UNSPECIFIED) return d
        }
        return SpaceDecision.KEEP
    }

    /** Pop the top frame when it matches; tolerate malformed input quietly. */
    private fun popMatching(frames: ArrayList<Frame>, kind: FrameKind): Frame? =
        if (frames.isNotEmpty() && frames.last().kind == kind) frames.removeLast() else null

    private fun indentWidthFor(
        first: Token,
        frames: List<Frame>,
        prevLineEndedContinuator: Boolean,
        config: FormatConfig,
    ): Int {
        val top = frames.lastOrNull() ?: return if (isCloser(first)) 0 else {
            if (continues(first, prevLineEndedContinuator)) config.continuationIndent else 0
        }

        // Line starts by closing the innermost construct: align to its opener line.
        if (isCloser(first)) return top.closeIndent

        var width = top.bodyIndent

        // Java colon-switch: statements inside the switch body sit one level
        // deeper than the `case`/`default` labels.
        if (top.kind == FrameKind.BRACE && top.blockKind == BlockKind.SWITCH &&
            !(first.type == TokenType.KEYWORD && (first.text == "case" || first.text == "default"))
        ) {
            width += config.indentSize
        }

        // Wrapped-expression continuation. Group frames already indent their
        // contents; only add the extra inside `{}` blocks (or at top level).
        if (top.kind == FrameKind.BRACE && continues(first, prevLineEndedContinuator)) {
            width += config.continuationIndent
        }
        return width
    }

    private fun isCloser(t: Token): Boolean =
        t.type == TokenType.RBRACE || t.type == TokenType.RPAREN || t.type == TokenType.RBRACKET

    private fun continues(first: Token, prevLineEndedContinuator: Boolean): Boolean =
        prevLineEndedContinuator || startsContinuation(first)

    private fun startsContinuation(t: Token): Boolean =
        t.type == TokenType.OPERATOR && t.text in CONTINUATION_START

    private fun isEolContinuator(tokens: List<Token>, index: Int): Boolean {
        val t = tokens[index]
        if (t.type != TokenType.OPERATOR || t.text !in CONTINUATION_EOL) return false
        // `import kotlinx.coroutines.*` ends in a wildcard, not a multiplication.
        val before = tokens.getOrNull(index - 1)
        return !(t.text == "*" && before != null && before.type == TokenType.OPERATOR && before.text == ".")
    }

    /** Re-indents `*`-gutter lines of multi-line block comments. */
    private fun emitToken(sb: StringBuilder, t: Token, indent: String, ctx: RuleContext) {
        if (t.type == TokenType.BLOCK_COMMENT && ctx.config.alignCommentStars && t.text.indexOf('\n') >= 0) {
            val lines = t.text.split('\n')
            sb.append(lines[0])
            for (li in 1 until lines.size) {
                sb.append('\n')
                val line = lines[li]
                val trimmed = line.trimStart()
                if (trimmed.startsWith("*")) {
                    sb.append(indent).append(' ').append(trimmed.trimEnd())
                } else {
                    // Not a star-gutter line (could be ASCII art) — keep verbatim.
                    sb.append(line.trimEnd())
                }
            }
        } else {
            sb.append(t.text)
        }
    }

    companion object {
        private val BRACELESS_CONTROL = setOf("if", "for", "while")

        /** Modifiers that may precede a property or an accessor. */
        private val ACCESSOR_MODIFIERS = setOf(
            "private", "protected", "internal", "public", "override", "open", "final", "abstract",
            "lateinit", "const", "inline", "external", "actual", "expect", "operator",
        )

        /** A line starting with one of these is a continuation of the previous line. */
        private val CONTINUATION_START = setOf(
            ".", "?.", "::", "?:", "&&", "||", "==", "!=", "<=",
            "+", "-", "*", "/", "%", "=", "&", "|", "^", "?", ":",
        )

        /**
         * A line ending with one of these makes the NEXT line a continuation.
         * `:` is deliberately absent — Java `case X:` labels end with a colon
         * and their bodies are indented by the switch logic, not continuation.
         */
        private val CONTINUATION_EOL = setOf(
            "=", "+", "-", "*", "/", "%", "&&", "||", "?:", "?.", ".", "::",
            "==", "!=", "<=", "&", "|", "^", "?",
            "+=", "-=", "*=", "/=", "%=",
        )
    }
}
