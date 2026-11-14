package dev.vlformat

import dev.vlformat.engine.Formatter
import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Token
import dev.vlformat.lexer.TokenType
import dev.vlformat.rules.RuleContext
import dev.vlformat.rules.SpaceDecision
import dev.vlformat.rules.SpacingRule
import dev.vlformat.rules.TokenRule
import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves the two extension points: custom token rules and spacing overrides. */
class ExtensibilityTest {

    /** Rewrites TODO line comments to FIXME — a whole-stream token rule. */
    private class TodoToFixmeRule : TokenRule {
        override val name = "todo-to-fixme"
        override val order = 40

        override fun apply(tokens: MutableList<Token>, ctx: RuleContext) {
            for (i in tokens.indices) {
                val t = tokens[i]
                if (t.type == TokenType.LINE_COMMENT && t.text.contains("TODO")) {
                    tokens[i] = t.copy(text = t.text.replace("TODO", "FIXME"))
                }
            }
        }
    }

    /** House style: no space before `{`. Overrides the default (order 100). */
    private class TightBraceRule : SpacingRule {
        override val name = "tight-braces"
        override val order = 10

        override fun spaceBetween(tokens: List<Token>, leftIndex: Int, rightIndex: Int, ctx: RuleContext): SpaceDecision {
            if (tokens[rightIndex].type == TokenType.LBRACE) return SpaceDecision.NONE
            return SpaceDecision.UNSPECIFIED
        }
    }

    @Test
    fun `custom token rule participates in the pipeline`() {
        val f = Formatter(extraTokenRules = listOf(TodoToFixmeRule()), discoverPlugins = false)
        assertEquals("val x = 1 // FIXME: rename\n", f.format("val x = 1 // TODO: rename", Language.KOTLIN))
    }

    @Test
    fun `custom spacing rule overrides the default by order`() {
        val f = Formatter(extraSpacingRules = listOf(TightBraceRule()), discoverPlugins = false)
        assertEquals("fun a(){\n    b()\n}\n", f.format("fun a() {\nb()\n}", Language.KOTLIN))
    }

    @Test
    fun `custom rules honor disabledRules too`() {
        val f = Formatter(
            config = dev.vlformat.config.FormatConfig(disabledRules = setOf("tight-braces")),
            extraSpacingRules = listOf(TightBraceRule()),
            discoverPlugins = false,
        )
        assertEquals("fun a() {\n    b()\n}\n", f.format("fun a() {\nb()\n}", Language.KOTLIN))
    }
}
