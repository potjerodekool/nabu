package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.type.DeclaredType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MethodResolverImplTest extends AbstractCompilerTest {

    @Test
    void isPotentiallyApplicable() {
        final var arrayListType = (DeclaredType) loadClass("java.util.ArrayList").asType();

        final Scope scope = mock(Scope.class);

        final var caller = getCompilerContext().getElementBuilders().typeElementBuilder()
                .kind(ElementKind.CLASS)
                .build();

        when(scope.getCurrentClass()).thenReturn(caller);

        final var methodResolver = (MethodResolverImpl) getCompilerContext().getMethodResolver();
        final var methods = methodResolver.getPotentiallyApplicableMethods(
                arrayListType,
                "get",
                scope
        );

        System.out.println(methods);
    }
}