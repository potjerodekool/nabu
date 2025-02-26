package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;
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
        final var methodInvocationTree = new MethodInvocationTree();
        final var target = new IdentifierTree("list");
        final var listClass = loader.resolveClass("java.util.List");
        final var stringClass = loader.resolveClass("java.lang.String");
        final var stringListType = types.getDeclaredType(
                listClass,
                stringClass.asType()
        );

        final var intType = types.getPrimitiveType(TypeKind.INT);


        final var listVariable = new VariableBuilder()
                .kind(ElementKind.VARIABLE)
                .name("list")
                .type(stringListType)
                .build();

        target.setSymbol(listVariable);

        methodInvocationTree.target(target);
        methodInvocationTree.name(new IdentifierTree("add"));

        final var indexArg = new LiteralExpressionTree(0);
        indexArg.setType(intType);

        final var elementArg = new IdentifierTree("value");
        final var variable = new VariableBuilder()
                .kind(ElementKind.VARIABLE)
                .name("value")
                .type(stringClass.asType())
                .build();

        elementArg.setSymbol(variable);

        methodInvocationTree.arguments(List.of(indexArg, elementArg));

        final var methodType = methodResolver.resolveMethod(methodInvocationTree);
        assertNotNull(methodType);

        final var printer = new TypePrinter();
        methodType.accept(printer, null);
        final var actual = printer.getText();

        assertEquals("""
                void (int, java.lang.String)""", actual);

    }

    @Test
    void resolveMethod2() {
        final var methodInvocationTree = new MethodInvocationTree();

        final var stringClass = loader.resolveClass("java.lang.String");
        final var localDateClass = loader.resolveClass("java.time.LocalDate");

        final var objectType = new ClassBuilder()
                .name("Object")
                .build()
                .asType();

        final var typeVar = types.getTypeVariable("Y", objectType, null);


        final var pathClass = new ClassBuilder()
                .name("Path")
                .typeParameter((TypeParameterElement) types.getTypeVariable("X", objectType, null).asElement())
                .build();

        final var personClass = new ClassBuilder()
                .name("Person")
                .enclosedElement(
                        new VariableBuilder()
                                .kind(ElementKind.FIELD)
                                .name("birthDay")
                                .type(localDateClass.asType())
                                .build()
                ).build();

        final var returnType = types.getDeclaredType(
                pathClass,
                typeVar
        );

        final var getMethod = new MethodBuilder()
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

        final var target = new IdentifierTree("e");
        target.setType(types.getDeclaredType(
                pathClass,
                personClass.asType()
        ));

        final var pathVariable = new VariableBuilder()
                .kind(ElementKind.VARIABLE)
                .name("list")
                .type(types.getDeclaredType(
                        pathClass,
                        personClass.asType()
                ))
                .build();

        target.setSymbol(pathVariable);

        methodInvocationTree.target(target);
        methodInvocationTree.name(new IdentifierTree("get"));

        final var typeArg = new IdentifierTree("LocalDate");
        typeArg.setType(localDateClass.asType());

        methodInvocationTree.typeArguments(typeArg);

        final var literal = new LiteralExpressionTree("birthDay");
        literal.setType(stringClass.asType());

        methodInvocationTree.arguments(List.of(literal));

        final var methodType = methodResolver.resolveMethod(methodInvocationTree);
        assertNotNull(methodType);

        final var printer = new TypePrinter();
        methodType.accept(printer, null);
        final var actual = printer.getText();

        assertEquals("""
                Path<java.time.LocalDate> (java.lang.String)""", actual);
    }

    @Test
    void test() {
        final var comparableClass = loader.resolveClass("java.lang.Comparable");

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

        final var localDateClass = loader.resolveClass("java.time.LocalDate");

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