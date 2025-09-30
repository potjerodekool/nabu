package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.compiler.frontend.parser.nabu.NabuCompilerParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static io.github.potjerodekool.nabu.test .TreeAssert.parseAndAssert;

class NabuCompilerVisitorTest {

    @Test
    void ordinaryCompilationUnit() throws IOException {
        final var code = """
                package io.github.potjerodekool.nabu.example;
                
                import static java.util.List.of;
                
                public class Utils {

                    static fun emptyList() : List {
                        return of();
                    }
                }
                
                
                """;

        parseAndAssert("""
                package something;
                
                import java.util.List;
                import static java.util.List.of;
                
                public class SomeClass {
                }
                """, NabuParser::compilationUnit);

        NabuCompilerParser.parse(new ByteArrayInputStream(code.getBytes()));

    }

    @Test
    void modularCompilationUnit() {
        parseAndAssert("""
                import java.util.List;
 
                @Module
                open module some.mymodule {
                    requires transitive static other.theremodule;
                    exports mystuff;
                    exports myotherstuff to foo, bar;
                    opens myotherstuff to foo, bar;
                    uses SomeThings;
                    provides SomeInterface with StandardSomeThing, AdvancedSomeThing;
                }""", NabuParser::modularCompilationUnit);
    }

    @Test
    void importDeclaration() {
        parseAndAssert("import java.util.List;", NabuParser::importDeclaration);
        parseAndAssert("import java.util.*;", NabuParser::importDeclaration);
        parseAndAssert("import static java.util.List.of;", NabuParser::importDeclaration);
        parseAndAssert("import static java.util.List.*;", NabuParser::importDeclaration);
    }

    @Test
    void visitExplicitConstructorInvocation() {
        parseAndAssert("super();", NabuParser::explicitConstructorInvocation);
        parseAndAssert("super(10);", NabuParser::explicitConstructorInvocation);
        parseAndAssert("<String>super();", NabuParser::explicitConstructorInvocation);
        parseAndAssert("this.super();", NabuParser::explicitConstructorInvocation);
    }

    @Test
    void visitFunctionModifier() {
        parseAndAssert("public", NabuParser::functionModifier);
        parseAndAssert("@Test", NabuParser::functionModifier);
    }

    @Test
    void visitRelationalExpression() {
        parseAndAssert("\"test\" instanceof String", NabuParser::relationalExpression);
    }

    @Test
    void visitPrimaryNoNewArray() {
        parseAndAssert("list.get(0)", NabuParser::primaryNoNewArray);
        parseAndAssert("\"test\".replace('A', 'B')", NabuParser::primaryNoNewArray);
        parseAndAssert("this", NabuParser::primaryNoNewArray);
        parseAndAssert("this.array[10]", NabuParser::primaryNoNewArray);
        parseAndAssert("this.array[10][2]", NabuParser::primaryNoNewArray);
        parseAndAssert("this.array[1][2][3]", NabuParser::primaryNoNewArray);
        parseAndAssert("this.someField", NabuParser::primaryNoNewArray);
        parseAndAssert("TT.this.getName()", NabuParser::primaryNoNewArray);
    }

    @Test
    void visitNormalClassDeclaration() {
        parseAndAssert("""
            public class SomeClass {
            }
            """, NabuParser::normalClassDeclaration);

        parseAndAssert("""
            public class SomeClass<T> extends Object implements SomeInterface {
            }
            """, NabuParser::normalClassDeclaration);

        parseAndAssert("""
                public class Box<E extends Object & java.lang.Comparable> {
                }
                """, NabuParser::normalClassDeclaration);
    }

    @Test
    void pNNA() {
        parseAndAssert(".new SomeClass<String>(1, 2, 3){}", NabuParser::pNNA,
                "."
        ); // 0
        parseAndAssert(".id", NabuParser::pNNA, "."); // 1
        parseAndAssert(".user.id", NabuParser::pNNA, "."); //1
        parseAndAssert(".array[10]", NabuParser::pNNA, "."); // 1
        parseAndAssert("[2]", NabuParser::pNNA); // 2
        parseAndAssert("[2][3]", NabuParser::pNNA); // 2
        parseAndAssert(".<String>getName()", NabuParser::pNNA, "."); //3
        parseAndAssert("::<String, Object>get", NabuParser::pNNA);
        parseAndAssert("::<String, Object>get.more", NabuParser::pNNA);
    }

    @Test
    void visitWhile() {
        parseAndAssert("""
                while(true)
                {
                
                }
                """, NabuParser::whileStatementNoShortIf);
    }

    @Test
    void functionInvocation() {
        parseAndAssert("""
                get(0)""", NabuParser::functionInvocation);
        parseAndAssert("""
                list.<String>get(0)""", NabuParser::functionInvocation);
        parseAndAssert(
                "List.<String>of(\"Hello\")",
                NabuParser::functionInvocation
        );
        parseAndAssert(
                "java.util.List.<String>of(\"Hello\")",
                NabuParser::functionInvocation
        );
        parseAndAssert(
                "super.<String>of(\"Hello\")",
                NabuParser::functionInvocation
        );
        parseAndAssert(
                "List.super.<String>of(\"Hello\")",
                NabuParser::functionInvocation
        );
    }

    @Test
    void test(){
        parseAndAssert("(InnerJoin<Company, Employee>) c.employees", NabuParser::expression);
    }

    @Test
    void functionDeclarationWithLambda(){
        parseAndAssert("""
                fun findCompanyByEmployeeFirstName(employeeFirstName : String): JpaPredicate<Company> {
                    return (c : Root<Company>, q : CriteriaQuery<?>, cb : CriteriaBuilder) -> {
                        var e = (InnerJoin<Company, Employee>) c.employees;
                        return e.firstName == employeeFirstName;
                    }
                    ;
                }
                """, NabuParser::functionDeclaration);
    }

    @Test
    void markerAnnotation() {
        parseAndAssert("@Test", NabuParser::markerAnnotation);
    }

    @Test
    void normalAnnotation() {
        parseAndAssert("@Value(name = \"Hello world!\")", NabuParser::normalAnnotation);
    }

    @Test
    void normalAnnotationWithEnum() {
        parseAndAssert("@Transactional(value = TxType.REQUIRED)", NabuParser::normalAnnotation);
    }

    @Test
    void arrayInitializer() {
        parseAndAssert("{}", NabuParser::arrayInitializer);
    }

    @Test
    void singleElementAnnotation() {
        parseAndAssert("@Value(\"Hello world!\")", NabuParser::singleElementAnnotation);
    }

    @Test
    void arrayType() {
        parseAndAssert("int[][]", NabuParser::arrayType);
        parseAndAssert("int @Deprecated[] @Indexed[]", NabuParser::arrayType);
    }

    @Test
    void classLiteral() {
        parseAndAssert("String.class", NabuParser::classLiteral);
        parseAndAssert("String[][].class", NabuParser::classLiteral);
        parseAndAssert("int.class", NabuParser::classLiteral);
        parseAndAssert("int[][].class", NabuParser::classLiteral);
        parseAndAssert("boolean.class", NabuParser::classLiteral);
        parseAndAssert("boolean[][].class", NabuParser::classLiteral);
    }

    @Test
    void functionDeclaration() {
        parseAndAssert("""
            fun fail(): String throws IOException, IllegalStateException {
            
            }
            """, NabuParser::functionDeclaration);
    }

    @Test
    void functionDeclarationWithReceiver() {
        parseAndAssert("""
            fun fail(this : MyClass, other : String): String {
            
            }
            """, NabuParser::functionDeclaration);

        parseAndAssert("""
            fun fail(MyClass.this : MyClass, other : String): String {
            
            }
            """, NabuParser::functionDeclaration);
    }

    @Test
    void classOrInterfaceType() {
        parseAndAssert("java.util.@Deprecated List<String>", NabuParser::classOrInterfaceType);
        parseAndAssert("java.util.@Deprecated Map<String, String>.@Deprecated Entry<String, String>", NabuParser::classOrInterfaceType);
    }

    @Test
    void tryWithResourcesStatement() {
        parseAndAssert("""
            try (var fis : FileInputStream = new FileInputStream(file.txt)) {
            
            }
            """, NabuParser::tryWithResourcesStatement);
    }

    @Test
    void unannPrimitiveType() {
        parseAndAssert("byte", NabuParser::unannPrimitiveType);
        parseAndAssert("short", NabuParser::unannPrimitiveType);
        parseAndAssert("int", NabuParser::unannPrimitiveType);
        parseAndAssert("long", NabuParser::unannPrimitiveType);
        parseAndAssert("char", NabuParser::unannPrimitiveType);
        parseAndAssert("float", NabuParser::unannPrimitiveType);
        parseAndAssert("double", NabuParser::unannPrimitiveType);
        parseAndAssert("boolean", NabuParser::unannPrimitiveType);
    }

    //@Test
    void constructorDeclaration() {
        parseAndAssert("private SomeClass() throws Exception { }", NabuParser::constructorDeclaration);
    }

    @Test
    void enhancedForStatement() {
        parseAndAssert("""
            for (var e in list)
            {
            
            }
            """, NabuParser::enhancedForStatement);
    }

    @Test
    void recordDeclaration() {
        parseAndAssert("""
                public record IdAndName<ID>(id : ID, name : String) implements WithId {
                    fun nameLength(): int {
                        return name.length();
                    }
                
                }
                """, NabuParser::recordDeclaration);
    }

    @Test
    void interfaceDeclaration() {
        parseAndAssert("""
            public interface Action {
            }
            """, NabuParser::interfaceDeclaration);
    }

    @Test
    void enumDeclaration() {
        parseAndAssert("""
            public enum State {
                OPENED(),
                CLOSED()
            }
            """, NabuParser::enumDeclaration);

        parseAndAssert("""
            public enum State {
                OPENED("opened"),
                CLOSED("closed")
            }
            """, NabuParser::enumDeclaration);
    }

    @Test
    void labeledStatement() {
        parseAndAssert("""
            label : while(true)
            {
                break;
            }
            """,
                NabuParser::labeledStatement);
    }

    @Test
    void continueStatement() {
        parseAndAssert("""
            continue loop;""",
                NabuParser::continueStatement);
    }

    @Test
    void synchronizedStatement() {
        parseAndAssert("""
                synchronized(this){
                    if(this.someField == null){
                        this.someField = "SomeValue";
                    }
                
                }
                """, NabuParser::synchronizedStatement);
    }

    @Test
    void throwStatement() {
        parseAndAssert("""
                throw new RuntimeException("Failed");""", NabuParser::throwStatement);
    }

    @Test
    void tryStatement() {
        parseAndAssert("""
                try {
                    call();
                }
                catch (e : CallFailedException){
                
                }
                catch (e : Exception){
                
                }
                finally {
                    cleanUp();
                }
                """, NabuParser::tryStatement);
    }

    @Test
    void yieldStatement() {
        parseAndAssert("""
                yield 10;""", NabuParser::yieldStatement);
    }

    @Test
    void assertStatement() {
        parseAndAssert(
                """
                        assert i > 10: "i should be greater than 10";""", NabuParser::assertStatement
        );
    }

    @Test
    void switchStatement() {
        parseAndAssert("""
                switch(i)
                {
                    case 1, 3 -> System.out.println("One or Three");
                    case 2 -> {
                        System.out.println("Two");
                    }
                
                    case null -> throw new NullPointerException();
                    default -> System.out.println("Other");
                }
                """, NabuParser::switchStatement);
    }

    @Test
    void switchStatementWithBlockStatementGroup() {
        parseAndAssert("""
                switch(i)
                {
                    case 1 : case 3 : {
                        System.out.println("One or Three");
                    }
                
                    case 2 : case null : throw new NullPointerException();
                    default : System.out.println("Other");
                }
                """, NabuParser::switchStatement);
    }

    @Test
    void arrayCreationExpressionWithoutInitializer() {
        parseAndAssert("new int[5]", NabuParser::arrayCreationExpressionWithoutInitializer);
        parseAndAssert("new Object[5]", NabuParser::arrayCreationExpressionWithoutInitializer);
    }

    @Test
    void recordComponent() {
        parseAndAssert("elements : String...", NabuParser::recordComponent);
    }

    //@Test
    void CompactConstructorDeclaration() {
        parseAndAssert("""
            public MyRecord {
                if (name == null) {
                    throw new NullPointerException();
                }
            }
            """, NabuParser::compactConstructorDeclaration);
    }

    @Test
    void typePattern() {
        parseAndAssert("""
                switch(value)
                {
                    case Integer i : {
                        i.toString();
                    }

                    default : {
                        value.toString();
                    }

                }
                """, NabuParser::switchStatement);

    }

    @Test
    void visitFieldDeclaration() {
        parseAndAssert("""
                private name : String = "Hello world!";""", NabuParser::fieldDeclaration);
    }

    @Test
    void visitForStatement() {
        parseAndAssert("""
            for (var i = 0;i < times;i++){
                result += 2;
            }
            """, NabuParser::statement);
    }

    @Test
    void interfaceFunctionDeclaration() {
        parseAndAssert("""
                        abstract fun getAnnotationMirrors(): List<? extends AnnotationMirror> ;""",
                NabuParser::interfaceFunctionDeclaration, actual -> actual + ";");
    }

    @Test
    void test2() {
        parseAndAssert("""
                private fun test(): void {
                    var main = new Main();
                    var other = new Dummy();
                    other.someDummyStuff();
                }
                """, NabuParser::functionDeclaration);
    }

    @Test
    void visitAnnotation() {
        parseAndAssert("""
                @RequestMapping(value = {"/test"})""", NabuParser::annotation);
    }

}