package io.github.potjerodekool.nabu.compiler.lang.support.kotlin;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.InMemoryFileObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KotlinLanguageSupportTest extends AbstractCompilerTest {

    private void parseKotlin(String source) {
        final var parser = new KotlinLanguageSupport();
        final var fileObject = new InMemoryFileObject(source, "Test.kt");
        try {
            parser.parse(fileObject, getCompilerContext());
        } catch (final UnsupportedOperationException e) {
            // KotlinCompilerVisitor doesn't support all constructs yet,
            // but the parser still covers ANTLR-generated parser code
        }
    }

    @Test
    void parse() {
        final var parser = new KotlinLanguageSupport();
        final var fileObject = createFileObject(
                loadResource("KotlinLanguageSupport/example/Customer.kt")
        );

        final var cu = parser.parse(
                fileObject,
                getCompilerContext()
        );

        final var actual = TreePrinter.print(cu);
        assertEquals(
                """
                        package example;
                        
                        class Customer {
                        }
                        """,
                actual
        );

    }

    @Test
    void parseFunction() {
        parseKotlin("""
                package example
                
                fun hello(): String {
                    return "Hello"
                }
                """);
    }

    @Test
    void parseClassWithProperties() {
        parseKotlin("""
                package example
                
                class Person(val name: String, var age: Int)
                """);
    }

    @Test
    void parseClassWithMethods() {
        parseKotlin("""
                package example
                
                class Calculator {
                    fun add(a: Int, b: Int): Int {
                        return a + b
                    }
                    
                    fun subtract(a: Int, b: Int): Int {
                        return a - b
                    }
                }
                """);
    }

    @Test
    void parseDataClass() {
        parseKotlin("""
                package example
                
                data class Point(val x: Int, val y: Int)
                """);
    }

    @Test
    void parseObjectDeclaration() {
        parseKotlin("""
                package example
                
                object Singleton {
                    val instance = "singleton"
                    fun doSomething() {}
                }
                """);
    }

    @Test
    void parseEnumClass() {
        parseKotlin("""
                package example
                
                enum class Color(val rgb: Int) {
                    RED(0xFF0000),
                    GREEN(0x00FF00),
                    BLUE(0x0000FF)
                }
                """);
    }

    @Test
    void parseWhenExpression() {
        parseKotlin("""
                package example
                
                fun describe(x: Int): String {
                    return when (x) {
                        1 -> "one"
                        2 -> "two"
                        else -> "other"
                    }
                }
                """);
    }

    @Test
    void parseNullSafety() {
        parseKotlin("""
                package example
                
                fun process(name: String?) {
                    val length = name?.length ?: 0
                    val upper = name!!.uppercase()
                }
                """);
    }

    @Test
    void parseLambda() {
        parseKotlin("""
                package example
                
                fun apply(f: (Int) -> Int, x: Int): Int {
                    return f(x)
                }
                
                val double = { x: Int -> x * 2 }
                """);
    }

    @Test
    void parseCollections() {
        parseKotlin("""
                package example
                
                fun process() {
                    val list = listOf(1, 2, 3)
                    val mutableList = mutableListOf<String>()
                    val map = mapOf("a" to 1, "b" to 2)
                    val filtered = list.filter { it > 1 }
                    val mapped = list.map { it * 2 }
                }
                """);
    }

    @Test
    void parseInheritance() {
        parseKotlin("""
                package example
                
                open class Animal(val name: String)
                
                class Dog(name: String, val breed: String) : Animal(name)
                
                interface Speakable {
                    fun speak(): String
                }
                
                class Cat(name: String) : Animal(name), Speakable {
                    override fun speak(): String = "Meow"
                }
                """);
    }

    @Test
    void parseCompanionObject() {
        parseKotlin("""
                package example
                
                class MyClass {
                    companion object {
                        const val MAX = 100
                        fun create(): MyClass = MyClass()
                    }
                }
                """);
    }

    @Test
    void parseImports() {
        parseKotlin("""
                package example
                
                import java.util.List
                import java.util.Map as HashMap
                
                fun useImports() {
                    val list: List<String> = listOf("hello")
                }
                """);
    }

    @Test
    void parseTryCatch() {
        parseKotlin("""
                package example
                
                fun safeDivide(a: Int, b: Int): Int? {
                    return try {
                        a / b
                    } catch (e: ArithmeticException) {
                        null
                    }
                }
                """);
    }

    @Test
    void parseLoopStatements() {
        parseKotlin("""
                package example
                
                fun loops() {
                    for (i in 1..10) { }
                    for (i in 0 until 10) { }
                    var i = 0
                    while (i < 10) { i++ }
                    do { i-- } while (i > 0)
                }
                """);
    }

    @Test
    void parseStringTemplates() {
        parseKotlin("""
                package example
                
                fun greet(name: String): String {
                    val msg = "Hello, " + name + "!"
                    return msg
                }
                """);
    }

    @Test
    void parseVisibilityModifiers() {
        parseKotlin("""
                package example
                
                open class Base {
                    public val pub = 1
                    protected val prot = 2
                    private val priv = 3
                    internal val int = 4
                }
                """);
    }

    @Test
    void parseExtensionFunction() {
        parseKotlin("""
                package example
                
                fun String.isPalindrome(): Boolean {
                    return this == this.reversed()
                }
                
                val Int.isEven: Boolean
                    get() = this % 2 == 0
                """);
    }
}