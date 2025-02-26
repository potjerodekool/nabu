package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.NabuParser;
import org.junit.jupiter.api.Test;

import static io.github.potjerodekool.nabu.test.TreeAssert.parseAndAssert;

class NabuCompilerVisitorTest {

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
        parseAndAssert("this", NabuParser::primaryNoNewArray);
        parseAndAssert("this.someField", NabuParser::primaryNoNewArray);
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
        parseAndAssert("""
            .new SomeClass<String>(1, 2, 3){
            }
            """, NabuParser::pNNA,
                "."
        );
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
    void methodInvocation() {
        parseAndAssert("""
                get(0)""", NabuParser::methodInvocation);
        parseAndAssert("""
                list.<String>get(0)""", NabuParser::methodInvocation);
    }

    @Test
    void test(){
        parseAndAssert("(InnerJoin<Company, Employee>) c.employees", NabuParser::expression);
    }

    @Test
    void test2(){
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
    void singleElementAnnotation() {
        parseAndAssert("@Value(\"Hello world!\")", NabuParser::singleElementAnnotation);
    }
}