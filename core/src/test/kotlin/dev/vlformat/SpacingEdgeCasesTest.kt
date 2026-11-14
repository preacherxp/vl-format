package dev.vlformat

import dev.vlformat.engine.Formatter
import dev.vlformat.lexer.Language
import kotlin.test.Test
import kotlin.test.assertEquals

class SpacingEdgeCasesTest {

    private val formatter = Formatter(discoverPlugins = false)

    private fun kt(src: String) = formatter.format(src, Language.KOTLIN)
    private fun java(src: String) = formatter.format(src, Language.JAVA)

    // ---- Kotlin-specific constructs ---------------------------------------

    @Test
    fun `negated type checks`() {
        assertEquals("if (x !is String) return\n", kt("if (x!is String) return"))
        assertEquals("if (x !in list) return\n", kt("if (x !in list) return"))
    }

    @Test
    fun `safe cast`() {
        assertEquals("val s = x as? String\n", kt("val s = x as?String"))
    }

    @Test
    fun `labeled returns and breaks keep their labels tight`() {
        assertEquals("list.map { return@map it }\n", kt("list.map { return@map it }"))
        assertEquals("break@loop\n", kt("break@loop"))
    }

    @Test
    fun `label definitions keep author spacing`() {
        assertEquals("loop@ for (i in 1..3) {}\n", kt("loop@ for (i in 1..3) {}"))
    }

    @Test
    fun `unbound callable reference gets a space, bound stays tight`() {
        assertEquals("val f = ::inc\n", kt("val f = ::inc"))
        assertEquals("val g = String::length\n", kt("val g = String :: length"))
        assertEquals("items.map(::transform)\n", kt("items.map(::transform)"))
    }

    @Test
    fun `not-null chains`() {
        assertEquals("val a = b!!.c!!\n", kt("val a = b !! . c !!"))
    }

    @Test
    fun `star projection untouched`() {
        assertEquals("val l: List<*> = listOf<Any>()\n", kt("val l:List<*> =listOf<Any>()"))
    }

    @Test
    fun `when branch with range`() {
        assertEquals(
            "when (n) {\n    in 1..5 -> \"low\"\n    else -> \"high\"\n}\n",
            kt("when(n){\nin 1 .. 5->\"low\"\nelse->\"high\"\n}"),
        )
    }

    @Test
    fun `object expression colon breathes`() {
        assertEquals("val r = object : Runnable {}\n", kt("val r = object:Runnable{}"))
    }

    @Test
    fun `generic constraint via where breathes`() {
        assertEquals("fun <T> f(x: T) where T : Comparable<T> {}\n", kt("fun <T> f(x:T) where T:Comparable<T>{}"))
    }

    @Test
    fun `property accessors`() {
        assertEquals("val ok get() = flag\n", kt("val ok get()=flag"))
    }

    @Test
    fun `delegation with by`() {
        assertEquals("val lazy1 by lazy { compute() }\n", kt("val lazy1 by lazy{compute()}"))
    }

    @Test
    fun `destructuring lambda parameters`() {
        assertEquals("map.forEach { (k, v) -> println(\"\$k=\$v\") }\n", kt("map.forEach{( k ,v )->println(\"\$k=\$v\")}"))
    }

    @Test
    fun `spread operator hugs`() {
        assertEquals("f(*args)\n", kt("f(* args)"))
    }

    @Test
    fun `function type declarations`() {
        assertEquals("val f: (Int) -> Unit = {}\n", kt("val f:(Int)->Unit={}"))
        assertEquals("fun g(cb: () -> Unit) {}\n", kt("fun g(cb:()->Unit){}"))
    }

    // ---- Java-specific constructs ------------------------------------------

    @Test
    fun `non-sealed stays hyphenated`() {
        assertEquals(
            "public non-sealed class Circle extends Shape {}\n",
            java("public non-sealed class Circle extends Shape{}"),
        )
    }

    @Test
    fun `wildcard generics`() {
        assertEquals("void f(List<? extends Number> xs) {}\n", java("void f(List<?extends Number> xs){}"))
        assertEquals("void g(List<? super Integer> xs) {}\n", java("void g(List<?super Integer> xs){}"))
    }

    @Test
    fun `nested generic method reference`() {
        assertEquals(
            "Map.Entry<String, List<Integer>> e = pick();\n",
            java("Map.Entry<String,List<Integer>> e=pick();"),
        )
    }

    @Test
    fun `empty for stays compact`() {
        assertEquals("for (;;) { spin(); }\n", java("for( ; ; ){spin();}"))
    }

    @Test
    fun `classic for loop`() {
        assertEquals(
            "for (int i = 0; i < n; i++) { use(i); }\n",
            java("for(int i=0;i < n;i ++ ){use(i);}"),
        )
    }

    @Test
    fun `prefix decrement in for update`() {
        assertEquals("for (int i = n; i > 0; --i) {}\n", java("for(int i=n;i > 0; -- i){}"))
    }

    @Test
    fun `array creation and initializer`() {
        assertEquals("int[] a = new int[10];\n", java("int [] a=new int [ 10 ] ;"))
        assertEquals("int[] b = {1, 2, 3};\n", java("int[] b={1,2,3};"))
    }

    @Test
    fun `anonymous class instantiation`() {
        assertEquals("Runnable r = new Runnable() {};\n", java("Runnable r=new Runnable(){};"))
    }

    @Test
    fun `assert with message colon`() {
        assertEquals("assert x>0 : \"must be positive\";\n", java("assert x>0:\"must be positive\";"))
    }

    @Test
    fun `nested ternary`() {
        assertEquals("int r = a ? b : c ? d : e;\n", java("int r=a?b:c?d:e;"))
    }

    @Test
    fun `instanceof pattern`() {
        assertEquals(
            "if (o instanceof String s) { use(s); }\n",
            java("if(o instanceof String s){use(s);}"),
        )
    }

    @Test
    fun `arrow switch with multiple labels`() {
        assertEquals(
            "return switch (n) {\n    case 1, 2 -> \"few\";\n    default -> \"many\";\n};\n",
            java("return switch(n){\ncase 1,2->\"few\";\ndefault->\"many\";\n};"),
        )
    }

    @Test
    fun `record declaration`() {
        assertEquals("record Point(int x, int y) {}\n", java("record Point( int x,int y ){}"))
    }

    @Test
    fun `annotation with array argument`() {
        assertEquals(
            "@Target({ElementType.TYPE, ElementType.METHOD}) @interface Marker {}\n",
            java("@Target( { ElementType.TYPE,ElementType.METHOD } )@interface Marker{}"),
        )
    }

    @Test
    fun `bitwise operators`() {
        assertEquals("int m = (a & b) | (c ^ d);\n", java("int m=(a&b)|(c^d);"))
        assertEquals("int s = flags << 2;\n", java("int s=flags<<2;"))
        assertEquals("int inv = ~mask;\n", java("int inv=~ mask;"))
    }

    @Test
    fun `compound assignments`() {
        assertEquals("x %= 3;\ny &= mask;\nz |= bit;\nw ^= k;\n", java("x%=3;\ny&=mask;\nz|=bit;\nw^=k;"))
    }

    @Test
    fun `throws clause`() {
        assertEquals("void f() throws IOException, JAXBException {}\n", java("void f()throws IOException,JAXBException{}"))
    }

    @Test
    fun `varargs in full signature`() {
        assertEquals("static void log(String fmt, Object... args) {}\n", java("static void log(String fmt,Object ... args){}"))
    }

    @Test
    fun `lambda with explicit params`() {
        assertEquals("BiFunction<Integer, Integer, Integer> add = (a, b) -> a + b;\n", java("BiFunction<Integer,Integer,Integer> add=(a,b)->a+b;"))
    }
}
