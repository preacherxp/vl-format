package dev.vlformat

import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Lexer
import dev.vlformat.lexer.Token
import dev.vlformat.lexer.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexerEdgeCasesTest {

    private fun lex(src: String, lang: Language = Language.KOTLIN): List<Token> =
        Lexer(src, lang).tokenize()

    private fun texts(src: String, lang: Language = Language.KOTLIN): List<String> =
        lex(src, lang).filter { it.type != TokenType.NEWLINE }.map { it.text }

    // ---- degenerate inputs ---------------------------------------------------

    @Test
    fun `empty source`() {
        assertEquals(emptyList(), lex(""))
    }

    @Test
    fun `whitespace only`() {
        assertTrue(lex("   \t  ").isEmpty())
        assertEquals(listOf(TokenType.NEWLINE, TokenType.NEWLINE), lex("\n\n").map { it.type })
    }

    @Test
    fun `comment only file`() {
        assertEquals(listOf(TokenType.LINE_COMMENT), lex("// just a note").map { it.type })
        assertEquals(listOf(TokenType.BLOCK_COMMENT), lex("/* note */").map { it.type })
    }

    // ---- line endings --------------------------------------------------------

    @Test
    fun `crlf and lone cr are newlines`() {
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.NEWLINE, TokenType.IDENTIFIER),
            lex("a\r\nb").map { it.type },
        )
        assertEquals(
            listOf(TokenType.IDENTIFIER, TokenType.NEWLINE, TokenType.IDENTIFIER),
            lex("a\rb").map { it.type },
        )
    }

    @Test
    fun `tabs count as space-before`() {
        val tokens = lex("a\tb")
        assertTrue(tokens[1].hadSpaceBefore)
    }

    // ---- unterminated constructs (must not swallow the file) -----------------

    @Test
    fun `unterminated string stops at newline`() {
        val tokens = lex("val s = \"oops\nval t = 1")
        assertEquals("\"oops", tokens.first { it.type == TokenType.STRING }.text)
        assertTrue(tokens.any { it.text == "t" })
    }

    @Test
    fun `unterminated char stops at newline`() {
        val tokens = lex("val c = 'x\nval d = 1", Language.KOTLIN)
        assertTrue(tokens.any { it.text == "d" })
    }

    @Test
    fun `unterminated block comment runs to eof without crash`() {
        val tokens = lex("/* forever\nand ever")
        assertEquals(1, tokens.size)
        assertEquals(TokenType.BLOCK_COMMENT, tokens[0].type)
    }

    @Test
    fun `unterminated raw string runs to eof`() {
        val tokens = lex("val s = \"\"\"no end")
        assertEquals(TokenType.STRING, tokens.last().type)
    }

    @Test
    fun `unterminated backtick identifier stops at newline`() {
        val tokens = lex("fun `broken\nval x = 1")
        assertTrue(tokens.any { it.text == "x" })
    }

    // ---- strings & templates -------------------------------------------------

    @Test
    fun `empty string and empty raw string`() {
        assertEquals(listOf("\"\""), texts("\"\""))
        assertEquals(listOf("\"\"\"\"\"\""), texts("\"\"\"\"\"\""))
    }

    @Test
    fun `dollar without brace is plain content`() {
        assertEquals(listOf("\"\$name ends\""), texts("\"\$name ends\""))
        assertEquals(listOf("\"a\$\""), texts("\"a\$\""))
        assertEquals(listOf("\"100\$\""), texts("\"100\$\""))
    }

    @Test
    fun `escaped dollar is not a template`() {
        assertEquals(listOf("\"\\\${x}\""), texts("\"\\\${x}\""))
    }

    @Test
    fun `deeply nested templates`() {
        val src = "\"a\${f(\"b\${g(\"c\")}d\")}e\""
        val tokens = lex("val s = $src")
        assertEquals(src, tokens.last().text)
    }

    @Test
    fun `template containing line comment`() {
        val src = "\"\${x // not really a comment terminator issue\n}\""
        val tokens = lex("val s = $src")
        assertEquals(TokenType.STRING, tokens.last().type)
    }

    @Test
    fun `raw string ending in extra quotes`() {
        val tokens = lex("val s = \"\"\"a\"\"\"\"")
        assertEquals("\"\"\"a\"\"\"\"", tokens.last().text)
    }

    // ---- numbers ---------------------------------------------------------

    @Test
    fun `number torture`() {
        assertEquals(listOf("0b1010"), texts("0b1010"))
        assertEquals(listOf("0xDEAD_BEEF"), texts("0xDEAD_BEEF"))
        assertEquals(listOf("1e10"), texts("1e10"))
        assertEquals(listOf("3.14e-2f"), texts("3.14e-2f"))
        assertEquals(listOf("42u"), texts("42u"))
        assertEquals(listOf("42uL"), texts("42uL"))
    }

    @Test
    fun `method call on int literal`() {
        assertEquals(listOf("1", ".", "toString", "(", ")"), texts("1.toString()"))
    }

    // ---- misc ------------------------------------------------------------

    @Test
    fun `unicode identifiers`() {
        assertEquals(listOf("val", "café", "=", "1"), texts("val café = 1"))
    }

    @Test
    fun `emoji survive inside strings`() {
        assertEquals(listOf("val", "s", "=", "\"🎉 done\""), texts("val s = \"🎉 done\""))
    }

    @Test
    fun `bom passes through as unknown`() {
        val tokens = lex("﻿val x = 1")
        assertEquals(TokenType.UNKNOWN, tokens[0].type)
        assertTrue(tokens.any { it.text == "x" })
    }

    @Test
    fun `operator at eof`() {
        assertEquals(listOf("val", "x", "=", "a", "+"), texts("val x = a +"))
    }

    @Test
    fun `at sign at eof`() {
        assertEquals(listOf("x", "@"), texts("x@"))
    }

    @Test
    fun `not-is and not-in stay two tokens`() {
        assertEquals(listOf("x", "!", "is", "String"), texts("x !is String"))
        assertEquals(listOf("x", "!", "in", "list"), texts("x !in list"))
    }

    @Test
    fun `every char of source is preserved across token stream`() {
        val nasty = "fun f() { val s = \"\${a.map { \"\$it\" }}\"; /* c1 /* c2 */ ok */ return s }"
        val joined = lex(nasty).joinToString("") { (if (it.hadSpaceBefore) " " else "") + it.text }
        assertEquals(nasty, joined)
    }
}
