package dev.vlformat

import dev.vlformat.lexer.Language
import dev.vlformat.lexer.Lexer
import dev.vlformat.lexer.Token
import dev.vlformat.lexer.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexerTest {

    private fun lex(src: String, lang: Language = Language.KOTLIN): List<Token> =
        Lexer(src, lang).tokenize()

    private fun texts(src: String, lang: Language = Language.KOTLIN): List<String> =
        lex(src, lang).map { it.text }

    @Test
    fun `simple declaration`() {
        val tokens = lex("val x = 1")
        assertEquals(listOf(TokenType.KEYWORD, TokenType.IDENTIFIER, TokenType.OPERATOR, TokenType.NUMBER), tokens.map { it.type })
    }

    @Test
    fun `string with escapes is one token`() {
        val tokens = lex("""val s = "a\"b\\c"""")
        assertEquals("\"a\\\"b\\\\c\"", tokens.last().text)
        assertEquals(TokenType.STRING, tokens.last().type)
    }

    @Test
    fun `kotlin template with nested braces and string is one token`() {
        val src = "val s = \"pre \${map { it.key }.let { \"x\" }} post\""
        val tokens = lex(src)
        assertEquals(TokenType.STRING, tokens.last().type)
        assertTrue(tokens.last().text.endsWith("post\""))
        assertEquals(4, tokens.size)
    }

    @Test
    fun `raw string with quotes and template`() {
        val src = "val s = \"\"\"line1\n  \"quoted\" \${x + 1}\nline3\"\"\""
        val tokens = lex(src)
        assertEquals(TokenType.STRING, tokens.last().type)
        assertTrue(tokens.last().text.startsWith("\"\"\""))
        assertTrue(tokens.last().text.endsWith("\"\"\""))
    }

    @Test
    fun `java text block`() {
        val src = "String s = \"\"\"\n  hello \"world\"\n  \"\"\";"
        val tokens = lex(src, Language.JAVA)
        assertTrue(tokens.any { it.type == TokenType.STRING && it.text.contains("hello") })
    }

    @Test
    fun `nested kotlin block comment`() {
        val src = "/* outer /* inner */ still comment */ val x = 1"
        val tokens = lex(src)
        assertEquals(TokenType.BLOCK_COMMENT, tokens[0].type)
        assertTrue(tokens[0].text.endsWith("still comment */"))
    }

    @Test
    fun `java block comment does not nest`() {
        val src = "/* outer /* inner */ int x;"
        val tokens = lex(src, Language.JAVA)
        assertEquals("/* outer /* inner */", tokens[0].text)
    }

    @Test
    fun `line comment stops at newline`() {
        val tokens = lex("// hi\nval x = 1")
        assertEquals(TokenType.LINE_COMMENT, tokens[0].type)
        assertEquals(TokenType.NEWLINE, tokens[1].type)
    }

    @Test
    fun `numbers with underscores hex and suffixes`() {
        assertEquals(listOf("1_000_000"), texts("1_000_000"))
        assertEquals(listOf("0xFF_EC"), texts("0xFF_EC"))
        assertEquals(listOf("3.14f"), texts("3.14f"))
        assertEquals(listOf("1e-9"), texts("1e-9"))
        assertEquals(listOf("10L"), texts("10L"))
    }

    @Test
    fun `range operator not eaten as decimal point`() {
        assertEquals(listOf("1", "..", "10"), texts("1..10"))
        assertEquals(listOf("0", "..<", "10"), texts("0..<10"))
    }

    @Test
    fun `shift right stays two tokens for generics safety`() {
        assertEquals(listOf("x", ">", ">", "2"), texts("x >> 2", Language.JAVA).filter { it != "\n" })
        assertEquals(listOf("List", "<", "List", "<", "String", ">", ">"), texts("List<List<String>>", Language.JAVA))
    }

    @Test
    fun `multi char operators munch greedily`() {
        assertEquals(listOf("a", "?:", "b"), texts("a ?: b"))
        assertEquals(listOf("a", "?.", "b"), texts("a?.b"))
        assertEquals(listOf("a", "!!"), texts("a!!"))
        assertEquals(listOf("Foo", "::", "bar"), texts("Foo::bar"))
        assertEquals(listOf("a", "===", "b"), texts("a === b"))
    }

    @Test
    fun `backtick identifier`() {
        val tokens = lex("fun `my test name`() {}")
        assertEquals("`my test name`", tokens[1].text)
        assertEquals(TokenType.IDENTIFIER, tokens[1].type)
    }

    @Test
    fun `hadSpaceBefore is tracked`() {
        val tokens = lex("a < b")
        assertTrue(tokens[1].hadSpaceBefore)
        val tight = lex("a<b")
        assertTrue(!tight[1].hadSpaceBefore)
    }

    @Test
    fun `char literals`() {
        assertEquals(listOf("'a'", "'\\''", "'\\u0041'"), texts("'a' '\\'' '\\u0041'", Language.JAVA))
    }

    @Test
    fun `kts shebang is a comment`() {
        val tokens = lex("#!/usr/bin/env kotlin\nprintln(1)")
        assertEquals(TokenType.LINE_COMMENT, tokens[0].type)
        assertEquals("#!/usr/bin/env kotlin", tokens[0].text)
    }

    @Test
    fun `lexing preserves every source character`() {
        // Rebuilding the source from tokens + spaces must lose nothing but whitespace.
        val src = "fun f(x: Int): String { return \"v=\${x * 2}\" } // done"
        val joined = lex(src).joinToString("") { (if (it.hadSpaceBefore) " " else "") + it.text }
        assertEquals(src, joined)
    }
}
