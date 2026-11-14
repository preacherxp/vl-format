package dev.vlformat.engine

import dev.vlformat.config.FormatConfig
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
 * Single pass, single StringBuilder sized to the input — this is the hot path.
 */
class Renderer(private val spacingRules: List<SpacingRule>) {

    private enum class FrameKind { BRACE, GROUP }

    private enum class BlockKind { NORMAL, SWITCH }

    /** One open `{`, `(` or `[`: body indent width and closer alignment. */
    private class Frame(
        val kind: FrameKind,
        val bodyIndent: Int,
        val closeIndent: Int,
        val blockKind: BlockKind = BlockKind.NORMAL,
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

        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.type == TokenType.NEWLINE) {
                prevLineEndedContinuator = if (!atLineStart && prevIndex >= 0) {
                    isEolContinuator(tokens[prevIndex])
                } else if (atLineStart) {
                    // Blank line: reset so the next line isn't a continuation.
                    false
                } else {
                    prevLineEndedContinuator
                }
                sb.append('\n')
                atLineStart = true
                sawSwitchOnLine = false
                i++
                continue
            }

            if (atLineStart) {
                lineIndent = indentWidthFor(t, frames, prevLineEndedContinuator, config)
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
                    frames.add(Frame(FrameKind.BRACE, anchor + config.indentSize, anchor, blockKind))
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

    private fun isEolContinuator(t: Token): Boolean =
        t.type == TokenType.OPERATOR && t.text in CONTINUATION_EOL

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
