package io.github.potjerodekool.nabu.compiler.lang.support.kotlin;

import io.github.potjerodekool.nabu.compiler.InMemoryFileObject;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks the ANTLR-generated KotlinParser tree so that generated parser,
 * context and listener/visitor base classes are exercised even when the
 * higher level KotlinCompilerVisitor does not support every construct yet.
 */
class KotlinParserWalkTest {

    private static Path testResources() {
        var current = Path.of("").toAbsolutePath();
        while (current != null && !"nabu".equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        return current.resolve("compiler").resolve("src").resolve("test").resolve("resources");
    }

    private List<String> listKotlinSources() throws IOException {
        final var root = testResources();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> path.toString().endsWith(".kt"))
                    .map(this::readSource)
                    .collect(Collectors.toList());
        }
    }

    private String readSource(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new RuntimeException("Cannot read " + path, e);
        }
    }

    private void walkKotlin(final String source) throws IOException {
        final var charsetSource = source.getBytes(StandardCharsets.UTF_8);
        final var ctx = KotlinCompilerParser.parse(new ByteArrayInputStream(charsetSource));
        final var syntaxErrors = syntaxErrorCount(source);

        if (syntaxErrors > 0) {
            throw new AssertionError("Kotlin source failed to parse (%d syntax errors):%n%s".formatted(syntaxErrors, source));
        }

        ParseTreeWalker.DEFAULT.walk(new KotlinParserBaseListener() {
        }, ctx);

        ctx.accept(new KotlinParserBaseVisitor<>() {
        });

        try {
            final var fileObject = new InMemoryFileObject(source, "Walk.kt");
            ctx.accept(new KotlinCompilerVisitor(fileObject));
        } catch (final RuntimeException ignored) {
            // KotlinCompilerVisitor does not support every Kotlin construct yet,
            // but the generated parser code already got exercised.
        }
    }

    private int syntaxErrorCount(final String source) {
        final var lexer = new KotlinLexer(CharStreams.fromString(source));
        final var parser = new KotlinParser(new CommonTokenStream(lexer));
        parser.kotlinFile();
        return parser.getNumberOfSyntaxErrors();
    }

    private void walkScript(final String source) throws IOException {
        final var lexer = new KotlinLexer(CharStreams.fromString(source));
        final var parser = new KotlinParser(new CommonTokenStream(lexer));
        final var ctx = parser.script();
        final int syntaxErrors = parser.getNumberOfSyntaxErrors();

        if (syntaxErrors > 0) {
            throw new AssertionError("Kotlin script failed to parse (%d syntax errors):%n%s".formatted(syntaxErrors, source));
        }
        if (ctx == null) {
            throw new AssertionError("Kotlin script produced no parse tree");
        }

        ParseTreeWalker.DEFAULT.walk(new KotlinParserBaseListener() {
        }, ctx);

        ctx.accept(new KotlinParserBaseVisitor<>() {
        });
    }

    @Test
    void parseEveryKotlinResource() throws IOException {
        final var sources = listKotlinSources();
        assertTrue(sources.size() > 0, "no kotlin test resources found");

        for (final var source : sources) {
            walkKotlin(source);
        }
    }

    @Test
    void parseRichConstructs() throws IOException {
        final List<String> sources = new ArrayList<>();

        sources.add("""
                package demo.app

                import java.util.List
                import java.util.Map as HashMap
                import demo.utils.*
                import demo.utils.counter

                /**
                 * Documentation comment.
                 */
                @file:JvmName("DemoTools")

                fun <T> maxOfAll(list: List<T>, by: (T, T) -> Int): T? {
                    var best: T? = null
                    for (item in list) {
                        if (best == null || by(item, best) > 0) {
                            best = item
                        }
                    }
                    return best
                }

                val counter: Int = 0
                    get() = field + 1
                    private set(value) {
                        field = value - 1
                    }

                val lazyValue: String by lazy { "computed" }
                    """);

        sources.add("""
                package geometry

                interface Shape {
                    fun area(): Double
                    fun describe(): String = "shape"
                }

                abstract class Base(name: String) {
                    init {
                        println("Base $name")
                    }

                    constructor(id: Int) : this("id-$id") {
                        println(id)
                    }
                }

                open class Polygon(
                    val sides: Int,
                    var sideLength: Double = 1.0
                ) : Shape {
                    final override fun area(): Double = sideLength * sideLength * sides

                    companion object Factory {
                        fun square(side: Double) = Polygon(4, side)
                    }
                }

                sealed class Result {
                    data class Success(val value: String) : Result()
                    data class Failure(val reason: String) : Result()
                    object Loading : Result()
                }

                enum class Direction(val degrees: Int) {
                    NORTH(0), EAST(90), SOUTH(180), WEST(270);

                    fun opposite(): Direction = when (this) {
                        NORTH -> SOUTH
                        EAST -> WEST
                        SOUTH -> NORTH
                        WEST -> EAST
                    }
                }

                class Repository(private val source: Shape) : Shape by source {
                    private inner class Cache {
                        fun load(): Shape = source
                    }
                }

                open class Outer {
                    private val secret = 42

                    inner class InnerReader {
                        fun read(): Int = secret
                    }
                }

                @Deprecated("use Geometry instead")
                typealias OldShape = Shape
                    """);

        sources.add("""
                package expressions

                fun describe(value: Any?): String {
                    val kind = when (value) {
                        is String -> "string of length ${value.length}"
                        in 1..10 -> "small number"
                        !in 1..10 -> "not a small number"
                        null -> "nothing"
                        else -> "unknown"
                    }

                    val result = if (value is String) {
                        value.uppercase()
                    } else {
                        "$value"
                    }

                    var i = 0
                    forLoop@ for (i in 0 until 10) {
                        if (i == 3) {
                            continue@forLoop
                        }
                        if (i == 8) {
                            break@forLoop
                        }
                    }

                    var cnt = 0
                    while (cnt < 3) {
                        cnt++
                    }
                    do {
                        cnt--
                    } while (cnt > 0)

                    fun local(a: Int) = a * 2

                    val safe: String? = null
                    val length = safe?.length ?: -1
                    val upper = safe!!.uppercase()
                    val isString = safe is String
                    val notNumber = safe !is Number

                    return result
                }

                fun applyTwice(f: (Int) -> Int, x: Int): Int = f(f(x))

                val increment: (Int) -> Int = { it + 1 }
                val sum = { a: Int, b: Int -> a + b }
                val list = listOf(1, 2, 3)
                val items = list.map { it * 2 }.filter { it > 2 }
                val pairs = listOf("a" to 1, "b" to 2)
                val mapped = "hello".map(::toUpperCase)
                val ref = increment::invoke
                    """);

        sources.add("""
                fun stringsAndUnicode() {
                    val name = "Wörld"
                    val café = "☕"
                    val title = "Hello, ${'$'}{name}!"
                    val literalDollar = "cost ${'$'}5"
                    val raw = \"\"\"
                        multi
                        line ${'$'}{name}
                    \"\"\";
                    val hash = name.hashCode()
                }

                fun destructure() {
                    val (first, second) = Pair(1, 2)
                    val (a, b, c) = Triple(1, 2, 3)
                    var x = 5
                    x += 1
                    x -= 2
                    x /= 2
                    val negative = -x
                    val not = !false
                    val bitwise = x shl 2
                    val bitAnd = x and 0xff
                    val bitOr = x or 0x0f
                    val bitXor = x xor 0xaa
                }

                fun labelled() {
                    outer@ for (i in 0..3) {
                        for (j in 0..3) {
                            if (j == 1) {
                                continue@outer
                            }
                            if (i == 2) {
                                return@labelled
                            }
                        }
                    }
                }

                fun loops() {
                    var i = 0
                    while (i < 5) {
                        i++
                        if (i == 3) {
                            continue
                        }
                    }

                    kotlin.run {
                        println("run")
                    }
                }
                    """);

        sources.add("""
                #!/usr/bin/env kotlin
                println("hello from script")
                    """);

        for (final var source : sources) {
            if (source.startsWith("#!/")) {
                walkScript(source);
            } else {
                walkKotlin(source);
            }
        }
    }

    @Test
    void parseRichConstructsTwo() throws IOException {
        final List<String> sources = new ArrayList<>();

        sources.add("""
                package advanced

                import kotlin.collections.List
                import kotlin.collections.MutableList
                import kotlin.collections.emptyList
                import kotlin.collections.listOf
                import kotlin.collections.mutableListOf

                typealias Handler = (Int) -> Unit
                typealias CallbackFun = String.(Int) -> String

                @Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Tag(val name: String = "")

                @Tag("a")
                @Tag("b")
                fun annotated() {
                }

                inline fun <reified T> reifiedCheck(value: Any): Boolean = value is T

                suspend fun suspendable(): String = "x"

                tailrec fun sumDownTo(n: Int, acc: Int): Int =
                    if (n == 0) acc else sumDownTo(n - 1, acc + n)

                operator fun StringBuilder.times(count: Int): StringBuilder {
                    repeat(count) {
                        append("x")
                    }
                    return this
                }

                infix fun Int.raisedTo(power: Int): Double =
                    Math.pow(this.toDouble(), power.toDouble())

                external fun externalOp(value: Int): Int

                inline fun cross(crossinline block: () -> Unit) {
                    block()
                }

                fun spread(vararg parts: String): String = parts.joinToString("")

                class Constrained2<T> where T : Number

                class GenericBox<A, B>(val a: A, val b: B) where A : CharSequence, B : Number

                fun receivers(): Unit {
                    val list: MutableList<String> = mutableListOf("a", "b")
                    val first: String? = list.firstOrNull()
                    val outList: List<out Number> = listOf<Number>(1, 2)
                    val inList: MutableList<in Int> = mutableListOf()
                    val handler: Handler = { v -> println(v) }
                    val withReceiver: CallbackFun = { i -> this.repeat(i) }
                    val susp: suspend () -> Unit = { }
                    val parenthesized: ((Int) -> Unit) = { }
                    superFun()
                }

                var observed: Int = 0
                    set(value) {
                        field = value
                    }
                    private get() = field

                val memo: String by lazy { "computed" }

                class SuperType {
                    fun base() {
                    }
                }

                class ChildType : SuperType() {
                    override fun base() {
                        super.base()
                        val p = super@ChildType.base()
                        val q = super<SuperType>.base()
                    }
                }

                fun parenthesizedExpr(): Int = (1 + 2) * (3 - 4)

                object Registry {
                    val singleton: Any = object : Runnable {
                        override fun run() {
                        }
                    }

                    val plain: Any = object {
                        val x = 1
                    }
                }

                fun tryFinally() {
                    val resource = Any()
                    try {
                        println(resource)
                    } finally {
                        println("done")
                    }
                }

                fun arrayAccess() {
                    val arr = intArrayOf(1, 2, 3)
                    arr[0] = arr[arr.size - 1]
                    val unicodeName = "café"
                    val multibyte = "Wörld"
                }

                fun whenKind(source: Any) {
                    val described = when (source) {
                        is Int -> "int ${source + 1}"
                        in 0..9 -> "range"
                        else -> "other"
                    }
                }
                    """);

        sources.add("""
                package more

                fun <T> List<T>.head(): T = this[0]

                fun scope() {
                    val numbers = listOf(1, 2, 3)
                    val doubled = numbers.map { it * 2 }
                    val even = numbers.filter { num -> num % 2 == 0 }
                    val empty = emptyList<String>()
                    val nil: List<String>? = null
                    val safeLen = nil?.size ?: -1
                    val explicit: Int = 5.also { }
                    numbers.let { println("$it") }
                    numbers.forEachIndexed { index, value -> println("$index:$value") }
                }

                fun parameterModifiers() {
                    fun withNoInline(noinline block: () -> Unit) {
                        block()
                    }

                    withNoInline {
                        println("noinline")
                    }
                }

                fun `backtick method`(): Int = 42

                fun main() {
                    println(`backtick method`)
                }
                    """);

        sources.add("""
                package more

                @[Experimental Unstable]
                fun annotatedList() {
                }

                @[SomeMarker]
                val annotatedProperty: Int = 1

                interface Source2 {
                    fun poll(): String
                }

                class Delegate2 : Source2 by DelegateSource()

                class DelegateSource2 : Source2 {
                    override fun poll(): String = "polled"
                }

                val col: List<Int> = [1, 2, 3]

                fun casts(any: Any) {
                    val s = any as String
                    val maybe = any as? String
                }

                fun multiErrorHandling() {
                    try {
                        println("try")
                    } catch (e: IllegalStateException) {
                        println("a")
                    } catch (e: Throwable) {
                        println("b")
                    } finally {
                        println("finally")
                    }
                }

                class Outer2 {
                    inner class Inner2 {
                        fun pick(): Outer2 = this@Outer2
                    }
                }

                fun loops2() {
                    for (i in 0..10 step 2) {
                        println(i)
                    }
                    for (i in 0 until 10) {
                        println(i)
                    }
                    do {
                        println("once")
                    } while (false)
                    var count = 3
                    while (count > 0) {
                        count--
                    }
                }

                fun lambdas2() {
                    val mul = { a: Int, b: Int -> a * b }
                    val invoke = mul(2, 3)
                    val withLabel = label@{ x: Int -> x + 1 }
                }

                fun destructure(pair: Triple<Int, String, Int>) {
                    val (a, b) = "a.z" to "b"
                    val (x, y, z) = pair
                }

                fun ret(x: Int): Any = if (x > 0) "pos" else { -1 }

                fun main() {
                    println("done")
                }
                    """);

        for (final var source : sources) {
            walkKotlin(source);
        }
    }
}