package dev.vlformat.rules

import dev.vlformat.config.FormatConfig
import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Token

/** Shared context passed to every rule invocation. */
class RuleContext(
    val language: Language,
    val config: FormatConfig,
)

/**
 * A pass over the token stream that may insert, remove, or replace tokens.
 * Token rules run in [order] (ascending) before rendering.
 */
interface TokenRule {
    /** Stable rule name; users can disable rules by name in config. */
    val name: String

    /** Execution order; built-ins use 0..100. */
    val order: Int
        get() = 50

    fun apply(tokens: MutableList<Token>, ctx: RuleContext)
}

/** What to put between two adjacent tokens on the same line. */
enum class SpaceDecision {
    /** No space. */
    NONE,
    /** Exactly one space. */
    ONE,
    /** Preserve whatever the author wrote (based on Token.hadSpaceBefore). */
    KEEP,
    /** This rule has no opinion; ask the next rule in the chain. */
    UNSPECIFIED,
}

/**
 * Decides horizontal spacing between adjacent significant tokens.
 * Rules are consulted in [order]; the first non-UNSPECIFIED decision wins.
 * The default rule (order 100) always decides, so the chain terminates.
 */
interface SpacingRule {
    val name: String

    val order: Int
        get() = 50

    /**
     * @param tokens the full token list (newlines included) for context queries
     * @param leftIndex index of the left token
     * @param rightIndex index of the right token (always leftIndex + 1 in v1)
     */
    fun spaceBetween(tokens: List<Token>, leftIndex: Int, rightIndex: Int, ctx: RuleContext): SpaceDecision
}

/**
 * Extension point discovered via [java.util.ServiceLoader]. Third parties add
 * rules by shipping a jar with a
 * `META-INF/services/dev.vlformat.rules.RuleProvider` entry — no changes to
 * vl-format itself.
 */
interface RuleProvider {
    fun tokenRules(): List<TokenRule> = emptyList()

    fun spacingRules(): List<SpacingRule> = emptyList()
}
