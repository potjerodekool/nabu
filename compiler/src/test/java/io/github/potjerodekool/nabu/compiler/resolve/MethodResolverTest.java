package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MethodResolverTest {

    private ClassElementLoader loader = null;
    private Types types;
    private MethodResolver methodResolver;

    @BeforeEach
    public void setup() {
        if (loader == null) {
            loader = new AsmClassElementLoader();
            loader.postInit();
            types = loader.getTypes();
            methodResolver = new MethodResolver(types);
        }
    }

    @Test
    void resolveMethod() {
        final var target = IdentifierTree.create("list");
        final var listClass = loader.loadClass("java.util.List");
        final var stringClass = loader.loadClass("java.lang.String");
        final var stringListType = types.getDeclaredType(
                listClass,
                stringClass.asType()
        );

        final var intType = types.getPrimitiveType(TypeKind.INT);


        final var listVariable = new VariableBuilder()
                .kind(ElementKind.LOCAL_VARIABLE)
                .name("list")
                .type(stringListType)
                .build();

        target.setSymbol(listVariable);


        final var indexArg = TreeMaker.literalExpressionTree(0, -1, -1);
        indexArg.setType(intType);

        final var elementArg = IdentifierTree.create("value");
        final var variable = new VariableBuilder()
                .kind(ElementKind.LOCAL_VARIABLE)
                .name("value")
                .type(stringClass.asType())
                .build();

        elementArg.setSymbol(variable);


        final var methodInvocationTree = TreeMaker.methodInvocationTree(
                target,
                IdentifierTree.create("add"),
                List.of(),
                List.of(indexArg, elementArg),
                -1,
                -1
        );

        final var methodType = methodResolver.resolveMethod(methodInvocationTree, null);
        assertNotNull(methodType);

        final var printer = new TypePrinter();
        methodType.accept(printer, null);
        final var actual = printer.getText();

        assertEquals("""
                void (int, java.lang.String)""", actual);

    }

    @Test
    void resolveMethod2() {
        final var stringClass = loader.loadClass("java.lang.String");
        final var localDateClass = loader.loadClass("java.time.LocalDate");

        final var objectType = new ClassBuilder()
                .name("Object")
                .build()
                .asType();

        final var typeVar = types.getTypeVariable("Y", objectType, null);


        final var pathClass = (ClassSymbol) new ClassBuilder()
                .name("Path")
                .typeParameter((TypeParameterElement) types.getTypeVariable("X", objectType, null).asElement())
                .build();

        final var personClass = new ClassBuilder()
                .name("Person")
                .enclosedElement(
                        (Symbol) new VariableBuilder()
                                .kind(ElementKind.FIELD)
                                .name("birthDay")
                                .type(localDateClass.asType())
                                .build()
                ).build();

        final var returnType = types.getDeclaredType(
                pathClass,
                typeVar
        );

        final var getMethod = (MethodSymbol) new MethodBuilder()
                .enclosingElement(pathClass)
                .returnType(returnType)
                .typeParameter((TypeParameterElement) typeVar.asElement())
                .argumentTypes(stringClass.asType())
                .name("get")
                .parameter(pb ->
                        pb
                                .name("attributeName")
                                .type(stringClass.asType()))
                .build();

        pathClass.addEnclosedElement(getMethod);

        final var target = IdentifierTree.create("e");
        target.setType(types.getDeclaredType(
                pathClass,
                personClass.asType()
        ));

        final var pathVariable = new VariableBuilder()
                .kind(ElementKind.LOCAL_VARIABLE)
                .name("list")
                .type(types.getDeclaredType(
                        pathClass,
                        personClass.asType()
                ))
                .build();

        target.setSymbol(pathVariable);


        final var typeArg = IdentifierTree.create("LocalDate");
        typeArg.setType(localDateClass.asType());

        final var literal = TreeMaker.literalExpressionTree("birthDay", -1,-1);
        literal.setType(stringClass.asType());

        final var methodInvocationTree = TreeMaker.methodInvocationTree(
                target,
                IdentifierTree.create("get"),
                List.of(typeArg),
                List.of(literal),
                -1,
                -1
        );

        final var methodType = methodResolver.resolveMethod(methodInvocationTree, null);
        assertNotNull(methodType);

        final var printer = new TypePrinter();
        methodType.accept(printer, null);
        final var actual = printer.getText();

        assertEquals("""
                Path<java.time.LocalDate> (java.lang.String)""", actual);
    }

    @Test
    void test() {
        final var comparableClass = loader.loadClass("java.lang.Comparable");

        final var objectType = new ClassBuilder()
                .name("Object")
                .build()
                .asType();

        final var typeVariable = types.getTypeVariable(
                "Y",
                types.getDeclaredType(
                        null,
                        comparableClass,
                        types.getWildcardType(
                                null,
                                types.getTypeVariable("Y", objectType, null)
                        )
                ),
                null
        );

        final var predicateClass = new ClassBuilder()
                .name("Predicate")
                .build();

        final var expressionClass = new ClassBuilder()
                .name("Expression")
                .typeParameter((TypeParameterElement) types.getTypeVariable("T", objectType, null).asElement())
                .build();

        final var methodType = types.getExecutableType(
                null,
                List.of(typeVariable),
                types.getDeclaredType(
                        null,
                        predicateClass
                ),
                List.of(
                        types.getDeclaredType(
                                null,
                                expressionClass,
                                types.getWildcardType(
                                        types.getTypeVariable("Y", objectType, null),
                                        null
                                )
                        ),
                        types.getTypeVariable("Y", objectType, null)
                ),
                List.of()
        );

        final var localDateClass = loader.loadClass("java.time.LocalDate");

        final var pathClass = new ClassBuilder()
                .name("Path")
                .typeParameter((TypeParameterElement) types.getTypeVariable("T", objectType, null).asElement())
                .build();

        final var argTypes = List.<TypeMirror>of(
                types.getDeclaredType(
                        null,
                        pathClass,
                        types.getDeclaredType(
                                null,
                                localDateClass
                        )
                ),
                types.getDeclaredType(
                        null,
                        localDateClass
                )
        );


        final var result = methodResolver.transform(
                methodType,
                List.of(),
                argTypes);

        final var printer = new TypePrinter();
        result.accept(printer, null);
        final var actual = printer.getText();
        assertEquals("Predicate (Expression<java.time.LocalDate>, java.time.LocalDate)", actual);
    }
}