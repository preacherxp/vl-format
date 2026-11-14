package dev.vlformat.engine

import dev.vlformat.config.FormatConfig
import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Lexer
import dev.vlformat.rules.AttachBraceRule
import dev.vlformat.rules.AttachClauseRule
import dev.vlformat.rules.BlankLineRule
import dev.vlformat.rules.DefaultSpacingRule
import dev.vlformat.rules.RuleContext
import dev.vlformat.rules.RuleProvider
import dev.vlformat.rules.SpacingRule
import dev.vlformat.rules.TokenRule
import java.util.ServiceLoader

/**
 * Public entry point: `Formatter().format(source, Language.KOTLIN)`.
 *
 * Pipeline: lex → token rules (ordered) → render (indentation + spacing chain).
 *
 * Extensibility: pass extra rules to the constructor, or ship a jar exposing
 * [RuleProvider] through `META-INF/services` and they are picked up
 * automatically when [discoverPlugins] is true.
 *
 * Instances are immutable and thread-safe; one instance can format many files
 * concurrently.
 */
class Formatter(
    private val config: FormatConfig = FormatConfig(),
    extraTokenRules: List<TokenRule> = emptyList(),
    extraSpacingRules: List<SpacingRule> = emptyList(),
    discoverPlugins: Boolean = true,
) {
    private val tokenRules: List<TokenRule>
    private val renderer: Renderer

    init {
        val tRules = ArrayList<TokenRule>()
        val sRules = ArrayList<SpacingRule>()

        tRules += listOf(BlankLineRule(), AttachBraceRule(), AttachClauseRule())
        sRules += DefaultSpacingRule()
        tRules += extraTokenRules
        sRules += extraSpacingRules

        if (discoverPlugins) {
            for (provider in ServiceLoader.load(RuleProvider::class.java)) {
                tRules += provider.tokenRules()
                sRules += provider.spacingRules()
            }
        }

        tokenRules = tRules
            .filter { it.name !in config.disabledRules }
            .sortedBy { it.order }
        renderer = Renderer(
            sRules.filter { it.name !in config.disabledRules }.sortedBy { it.order },
        )
    }

    /** Format [source] and return the result; the input is never mutated. */
    fun format(source: String, language: Language): String {
        val ctx = RuleContext(language, config)
        val tokens = Lexer(source, language).tokenize()
        for (rule in tokenRules) rule.apply(tokens, ctx)
        return renderer.render(tokens, ctx, source.length)
    }

    /** True when [source] is already formatted. */
    fun isFormatted(source: String, language: Language): Boolean =
        format(source, language) == source
}
