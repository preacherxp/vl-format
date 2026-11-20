package dev.vlformat.rules

import dev.vlformat.lexer.Keywords
import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Token
import dev.vlformat.lexer.TokenType

/**
 * The built-in spacing table. Always returns a decision (never UNSPECIFIED),
 * so it terminates the spacing chain at order 100. Custom [SpacingRule]s with
 * lower order values can override any pair.
 *
 * Philosophy: normalize the unambiguous cases hard (commas, operators, braces,
 * keywords), and KEEP author spacing where token-level information is genuinely
 * ambiguous (`<` / `>`, which may be generics, comparisons, or shifts).
 */
class DefaultSpacingRule : SpacingRule {
    override val name = "default-spacing"
    override val order = 100

    override fun spaceBetween(tokens: List<Token>, leftIndex: Int, rightIndex: Int, ctx: RuleContext): SpaceDecision {
        val l = tokens[leftIndex]
        val r = tokens[rightIndex]
        val lang = ctx.language

        // Comments keep one space of separation from code on the same line.
        if (r.isComment || l.isComment) return SpaceDecision.ONE

        // Annotations: `@Foo`, `@[...]`. Keep author spacing before keywords so
        // both `@get:JvmName` and labels like `loop@ for` survive.
        if (l.type == TokenType.AT) {
            return if (r.type == TokenType.KEYWORD) SpaceDecision.KEEP else SpaceDecision.NONE
        }

        // Kotlin `return@label` / `x @Ann`: the gap before `@` is meaningful.
        if (lang == Language.KOTLIN && r.type == TokenType.AT) return SpaceDecision.KEEP

        // Java `non-sealed` is one hyphenated keyword; never space it apart.
        if (lang == Language.JAVA) {
            if (l.text == "non" && r.text == "-" && tokens.getOrNull(rightIndex + 1)?.text == "sealed") {
                return SpaceDecision.NONE
            }
            if (l.text == "-" && r.text == "sealed" && tokens.getOrNull(leftIndex - 1)?.text == "non") {
                return SpaceDecision.NONE
            }
        }

        // Member access & friends never get spaces: `a.b`, `a?.b`, `Foo::bar`, `1..10`.
        if (l.type == TokenType.OPERATOR && l.text in TIGHT_OPERATORS) {
            // `x!!.y`, `f!!()`, `a!![0]` hug; `x!! in y` and `x!! == y` keep a
            // binary gap (`x!!in` and `x!!==` don't parse in Kotlin).
            val postfixThen = l.text == "!!" &&
                !(r.type == TokenType.OPERATOR && r.text in TIGHT_OPERATORS_BEFORE) &&
                r.type != TokenType.LPAREN && r.type != TokenType.LBRACKET
            if (!postfixThen) return SpaceDecision.NONE
        }
        if (r.type == TokenType.OPERATOR && r.text in TIGHT_OPERATORS_BEFORE) {
            // `::` and `!!` hug only a value on their left (`Foo::bar`, `x!!`);
            // unbound references like `val f = ::inc` keep the binary gap.
            val postfixLike = r.text == "::" || r.text == "!!"
            if (!postfixLike || l.isWordLike ||
                l.type == TokenType.RPAREN || l.type == TokenType.RBRACKET
            ) {
                return SpaceDecision.NONE
            }
        }

        // Separators: `f(a, b);` — but `for (;;)` and `f(a,)` stay closed up.
        if (r.type == TokenType.COMMA || r.type == TokenType.SEMICOLON) return SpaceDecision.NONE
        if (l.type == TokenType.COMMA || l.type == TokenType.SEMICOLON) {
            return if (r.type == TokenType.RPAREN) SpaceDecision.NONE else SpaceDecision.ONE
        }

        // Parentheses.
        if (r.type == TokenType.LPAREN) return spaceBeforeLParen(l, lang)
        if (l.type == TokenType.LPAREN) return SpaceDecision.NONE
        if (r.type == TokenType.RPAREN) return SpaceDecision.NONE
        // `if (x) return`, `(int) cast`, `) @interface` — `).foo`/`)(`/`);`
        // are handled above.
        if (l.type == TokenType.RPAREN && (r.isWordLike || r.type == TokenType.AT)) return SpaceDecision.ONE

        // Brackets: `a[i]`, `int[]`, `int[] a`.
        if (l.type == TokenType.LBRACKET || r.type == TokenType.RBRACKET) return SpaceDecision.NONE
        if (r.type == TokenType.LBRACKET) {
            return if (l.isWordLike || l.type == TokenType.RPAREN || l.type == TokenType.RBRACKET) {
                SpaceDecision.NONE
            } else {
                SpaceDecision.ONE
            }
        }
        if (l.type == TokenType.RBRACKET && r.isWordLike) return SpaceDecision.ONE

        // Braces: K&R attach + single-line lambdas `{ x -> x * 2 }`.
        // Empty blocks stay tight: `{}`. Java array/annotation initializers
        // hug their elements: `int[] a = {1, 2};`, `@Target({TYPE})`.
        if (l.type == TokenType.LBRACE && r.type == TokenType.RBRACE) return SpaceDecision.NONE
        if (lang == Language.JAVA) {
            if (l.type == TokenType.LBRACE && isInitializerBrace(tokens, leftIndex)) return SpaceDecision.NONE
            if (r.type == TokenType.RBRACE && isInitializerRBrace(tokens, rightIndex)) return SpaceDecision.NONE
        }
        if (r.type == TokenType.LBRACE) return SpaceDecision.ONE
        if (l.type == TokenType.LBRACE) return SpaceDecision.ONE
        if (r.type == TokenType.RBRACE) return SpaceDecision.ONE
        if (l.type == TokenType.RBRACE) return SpaceDecision.ONE

        // Angle brackets are ambiguous at token level (generics vs comparison
        // vs shift halves) — preserve the author's choice. No exceptions: a
        // glued `>=`/`>>` is always an operator in valid code (Kotlin and Java
        // both reject `Map<K, V>=x`), and `f(a<b, c>=d)` must stay intact.
        if (isAngle(l) || isAngle(r)) return SpaceDecision.KEEP

        // Colons need context (types, ternary, case labels, use-site targets).
        if (r.type == TokenType.OPERATOR && r.text == ":") return spaceBeforeColon(tokens, leftIndex, rightIndex, lang)
        if (l.type == TokenType.OPERATOR && l.text == ":") return spaceAfterColon(tokens, leftIndex, lang)

        // `?`: Kotlin nullable marker hugs its type (`String?`); Java ternary
        // breathes. After a Kotlin `?` we fall through so `String? = null`
        // still gets a space from the `=` rule below.
        if (r.type == TokenType.OPERATOR && r.text == "?") {
            return if (lang == Language.KOTLIN) SpaceDecision.NONE else SpaceDecision.ONE
        }
        if (l.type == TokenType.OPERATOR && l.text == "?" && lang == Language.JAVA) {
            return SpaceDecision.ONE
        }

        // Increment/decrement: postfix `x++` hugs, `a + ++b` keeps the binary gap.
        if (r.type == TokenType.OPERATOR && (r.text == "++" || r.text == "--")) {
            return if (l.isWordLike || l.type == TokenType.RPAREN || l.type == TokenType.RBRACKET) {
                SpaceDecision.NONE
            } else {
                SpaceDecision.ONE
            }
        }
        if (l.type == TokenType.OPERATOR && (l.text == "++" || l.text == "--" || l.text == "!" || l.text == "~")) {
            return SpaceDecision.NONE
        }
        if (r.type == TokenType.OPERATOR && (r.text == "!" || r.text == "~")) return SpaceDecision.ONE

        // `+ - * &` may be unary/spread: decide by what precedes them.
        if (l.type == TokenType.OPERATOR && l.text in MAYBE_UNARY) {
            val before = prevSignificant(tokens, leftIndex)
            val binary = before != null && (
                before.isWordLike && !(before.type == TokenType.KEYWORD && before.text in UNARY_CONTEXT_KEYWORDS) ||
                    before.type == TokenType.RPAREN || before.type == TokenType.RBRACKET
                )
            return if (binary) SpaceDecision.ONE else SpaceDecision.NONE
        }

        // `=` glued to a `>` is the right half of a `>=` comparison (the lexer
        // never fuses `>=`; see the operator table). Keep author spacing after
        // it, like the other angle-bracket cases.
        if (l.type == TokenType.OPERATOR && l.text == "=" && !l.hadSpaceBefore) {
            val before = tokens.getOrNull(leftIndex - 1)
            if (before != null && before.type == TokenType.OPERATOR && before.text == ">") {
                return SpaceDecision.KEEP
            }
        }

        // Remaining operators are binary: one space on both sides.
        if (l.type == TokenType.OPERATOR || r.type == TokenType.OPERATOR) return SpaceDecision.ONE

        // Word next to word: `int x`, `val name`, `new Foo`, `x is String`.
        if (l.isWordLike && r.isWordLike) return SpaceDecision.ONE

        return SpaceDecision.KEEP
    }

    private fun spaceBeforeLParen(l: Token, lang: Language): SpaceDecision = when (l.type) {
        TokenType.KEYWORD -> when (l.text) {
            in Keywords.CONTROL -> SpaceDecision.ONE
            "this", "super", "constructor", "get", "set" -> SpaceDecision.NONE
            // `suspend () -> Unit` is a function type; `suspend(x)` could be a call.
            "suspend" -> SpaceDecision.KEEP
            // Kotlin soft keywords are ordinary names in call position:
            // `expect(1)`, `data(x)`, `value(v)` are function calls.
            else -> if (lang == Language.KOTLIN && l.text !in Keywords.KOTLIN_HARD) {
                SpaceDecision.NONE
            } else {
                SpaceDecision.ONE
            }
        }
        TokenType.IDENTIFIER, TokenType.RPAREN, TokenType.RBRACKET, TokenType.STRING -> SpaceDecision.NONE
        TokenType.RBRACE -> SpaceDecision.NONE // `{ ... }()` immediate invocation
        TokenType.LPAREN -> SpaceDecision.NONE
        // `listOf<Int>()`: a `>` hugging its left side closes type arguments.
        // A spaced `a > (b)` or `a < (b)` is a comparison: keep author spacing.
        TokenType.OPERATOR -> when {
            l.text == ">" && !l.hadSpaceBefore -> SpaceDecision.NONE
            l.text == ">" || l.text == "<" -> SpaceDecision.KEEP
            else -> SpaceDecision.ONE
        }
        else -> SpaceDecision.ONE
    }

    private fun isAngle(t: Token): Boolean =
        t.type == TokenType.OPERATOR && (t.text == "<" || t.text == ">")

    /** Java `{` opens an array/annotation initializer (not a code block)? */
    private fun isInitializerBrace(tokens: List<Token>, braceIndex: Int): Boolean {
        val before = prevSignificant(tokens, braceIndex) ?: return false
        return before.type == TokenType.COMMA || before.type == TokenType.LPAREN ||
            before.type == TokenType.LBRACE ||
            (before.type == TokenType.OPERATOR && before.text == "=")
    }

    /** Bounded back-scan: does this `}` close an initializer `{`? */
    private fun isInitializerRBrace(tokens: List<Token>, rbraceIndex: Int): Boolean {
        var depth = 1
        var steps = 0
        var k = rbraceIndex - 1
        while (k >= 0 && steps < SCAN_CAP) {
            when (tokens[k].type) {
                TokenType.RBRACE -> depth++
                TokenType.LBRACE -> {
                    depth--
                    if (depth == 0) return isInitializerBrace(tokens, k)
                }
                else -> {}
            }
            k--
            steps++
        }
        return false
    }

    private fun prevSignificant(tokens: List<Token>, from: Int): Token? {
        for (k in from - 1 downTo 0) {
            if (tokens[k].type != TokenType.NEWLINE) return tokens[k]
        }
        return null
    }

    private fun spaceBeforeColon(tokens: List<Token>, leftIndex: Int, colonIndex: Int, lang: Language): SpaceDecision {
        // Annotation use-site target: `@file:JvmName` — colon hugs both sides.
        if (tokens[leftIndex].type == TokenType.IDENTIFIER &&
            tokens.getOrNull(leftIndex - 1)?.type == TokenType.AT
        ) {
            return SpaceDecision.NONE
        }
        val stmt = statementContext(tokens, colonIndex, lang)
        return when (lang) {
            Language.KOTLIN -> when (stmt) {
                StmtContext.CLASS_HEADER -> SpaceDecision.ONE // `class A : B`, `constructor() : this()`
                else -> SpaceDecision.NONE // `val x: Int`, `fun f(): T`
            }
            Language.JAVA -> when (stmt) {
                StmtContext.TERNARY, StmtContext.FOREACH, StmtContext.ASSERT -> SpaceDecision.ONE
                else -> SpaceDecision.NONE // `case FOO:`, labels
            }
        }
    }

    private fun spaceAfterColon(tokens: List<Token>, colonIndex: Int, lang: Language): SpaceDecision {
        // `@file:JvmName`
        if (tokens.getOrNull(colonIndex - 2)?.type == TokenType.AT) return SpaceDecision.NONE
        return SpaceDecision.ONE
    }

    private enum class StmtContext { CLASS_HEADER, TERNARY, FOREACH, ASSERT, OTHER }

    /**
     * Cheap backward scan (capped) to classify the statement a colon lives in.
     * Stops at `;`, `{`, `}` or after [SCAN_CAP] tokens.
     */
    private fun statementContext(tokens: List<Token>, index: Int, lang: Language): StmtContext {
        var parenDepth = 0
        // Set when the scan crosses an unmatched `(` — the colon sits inside a
        // parameter/argument list, so `class`/`constructor` further left do NOT
        // make it a class-header colon (`class A(val x: Int)`).
        var insideParens = false
        var steps = 0
        var k = index - 1
        while (k >= 0 && steps < SCAN_CAP) {
            val t = tokens[k]
            when (t.type) {
                TokenType.SEMICOLON, TokenType.LBRACE, TokenType.RBRACE -> return StmtContext.OTHER
                // Kotlin statements end at a line break outside parentheses; without
                // this, `expect class X` on an earlier line made a later
                // `): Result` look like a class-header colon.
                TokenType.NEWLINE -> if (lang == Language.KOTLIN && parenDepth == 0 && !insideParens) {
                    return StmtContext.OTHER
                }
                TokenType.RPAREN -> parenDepth++
                TokenType.LPAREN -> {
                    if (parenDepth == 0) {
                        val before = prevSignificant(tokens, k)
                        if (lang == Language.JAVA && before?.type == TokenType.KEYWORD && before.text == "for") {
                            return StmtContext.FOREACH
                        }
                        insideParens = true
                    } else {
                        parenDepth--
                    }
                }
                TokenType.OPERATOR -> {
                    if (lang == Language.JAVA && t.text == "?" && parenDepth == 0 && !insideParens) {
                        return StmtContext.TERNARY
                    }
                }
                TokenType.KEYWORD -> when (t.text) {
                    "class", "interface", "object", "enum", "constructor", "where" ->
                        return if (insideParens) StmtContext.OTHER else StmtContext.CLASS_HEADER
                    "assert" -> return StmtContext.ASSERT
                    "case" -> return StmtContext.OTHER
                    else -> {}
                }
                else -> {}
            }
            k--
            steps++
        }
        return StmtContext.OTHER
    }

    companion object {
        private const val SCAN_CAP = 96

        /** No space AFTER these. */
        private val TIGHT_OPERATORS = setOf(".", "?.", "::", "..", "..<", "!!")

        /** No space BEFORE these (`...` is Java varargs: `T... args`). */
        private val TIGHT_OPERATORS_BEFORE = setOf(".", "?.", "::", "..", "..<", "!!", "...")

        /** Operators that are unary when not preceded by a value. */
        private val MAYBE_UNARY = setOf("+", "-", "*", "&")

        /** Keywords after which `+`/`-`/`*` are unary: `return -1`, `case -1:`. */
        private val UNARY_CONTEXT_KEYWORDS = setOf(
            "return", "case", "in", "else", "yield", "throw", "assert", "when", "while", "if", "do",
        )
    }
}
