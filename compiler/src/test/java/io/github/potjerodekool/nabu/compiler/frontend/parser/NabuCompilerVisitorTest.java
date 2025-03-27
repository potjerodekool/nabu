package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.NabuParser;
import org.junit.jupiter.api.Test;

import static io.github.potjerodekool.nabu.test.TreeAssert.parseAndAssert;

class NabuCompilerVisitorTest {

    @Test
    void ordinaryCompilationUnit() {
        parseAndAssert("""
                package something;
                
                import java.util.List;
                
                public class SomeClass {
                }
                """, NabuParser::ordinaryCompilationUnit);
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
                return e.firstName == employeeFirstName;}
                ;}
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
        parseAndAssert("{ }", NabuParser::arrayInitializer);
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
            try (FileInputStream fis = new FileInputStream(file.txt)) {
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
            for (var e : list)
            {
            }
            """, NabuParser::enhancedForStatement);
    }

}