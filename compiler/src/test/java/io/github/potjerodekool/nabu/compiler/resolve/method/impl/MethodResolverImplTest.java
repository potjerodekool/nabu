package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.util.TypePrinter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MethodResolverImplTest extends AbstractCompilerTest {

    @Test
    void resolveGet() {
        final var arrayListClass = loadClass("java.util.ArrayList");
        final var stringType = loadClass(Constants.STRING).asType();
        final var listOfStringType = getCompilerContext().getTypes().getDeclaredType(
                arrayListClass,
                stringType
        );

        final Scope scope = mock(Scope.class);

        final var caller = getCompilerContext().getElementBuilders().typeElementBuilder()
                .kind(ElementKind.CLASS)
                .build();

        when(scope.getCurrentClass()).thenReturn(caller);

        final var intType = getCompilerContext().getTypes().getPrimitiveType(TypeKind.INT);
        final var argument = TreeMaker.literalExpressionTree(1, -1, -1);
        argument.setType(intType);

        final var methodSelect = TreeMaker.fieldAccessExpressionTree(
                TreeMaker.identifier("list", -1, -1),
                TreeMaker.identifier("get", -1, -1),
                -1,
                -1
        );

        final var methodInvocation = TreeMaker.methodInvocationTree(
                methodSelect,
                List.of(),
                List.of(argument),
                -1,
                -1
        );

        final var methodResolver = (MethodResolverImpl) getCompilerContext().getMethodResolver();
        final var resolveMethod = methodResolver.tryResolveMethod(
                methodInvocation,
                listOfStringType,
                "get",
                scope
        );

        final var actual = TypePrinter.print(resolveMethod);
        final var expected = "java.lang.String get(int)";
        assertEquals(expected, actual);
    }

    @Test
    void resolveForEach() {
        final var arrayListClass = loadClass("java.util.ArrayList");
        final var stringType = loadClass(Constants.STRING).asType();
        final var listOfStringType = getCompilerContext().getTypes().getDeclaredType(
                arrayListClass,
                stringType
        );

        final Scope scope = mock(Scope.class);

        final var caller = getCompilerContext().getElementBuilders().typeElementBuilder()
                .kind(ElementKind.CLASS)
                .build();

        when(scope.getCurrentClass()).thenReturn(caller);

        final var consumerClass = loadClass("java.util.function.Consumer");
        final var stringConsumerType = getCompilerContext().getTypes().getDeclaredType(
                consumerClass,
                stringType
        );

        final var argument = IdentifierTree.create("consumer");
        argument.setType(stringConsumerType);

        final var methodSelect = TreeMaker.fieldAccessExpressionTree(
                TreeMaker.identifier("list", -1, -1),
                TreeMaker.identifier("forEach", -1, -1),
                -1,
                -1
        );

        final var methodInvocation = TreeMaker.methodInvocationTree(
                methodSelect,
                List.of(),
                List.of(argument),
                -1,
                -1
        );

        final var methodResolver = (MethodResolverImpl) getCompilerContext().getMethodResolver();
        final var resolveMethod = methodResolver.tryResolveMethod(
                methodInvocation,
                listOfStringType,
                "forEach",
                scope
        );

        final var actual = TypePrinter.print(resolveMethod);
        final var expected = "void forEach(java.util.function.Consumer<? super java.lang.String>)";
        assertEquals(expected, actual);
    }
}