package io.github.potjerodekool.nabu.compiler.resolve.impl.box;

import io.github.potjerodekool.nabu.compiler.type.impl.CPrimitiveType;
import io.github.potjerodekool.nabu.compiler.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.type.PrimitiveType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoxerTest {

    private MethodResolver methodResolver;

    @BeforeEach
    void setup() {
        methodResolver = mock(MethodResolver.class);
        when(methodResolver.resolveMethod(any(MethodInvocationTree.class), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void intBoxerReturnsExpressionUnchangedForNonPrimitiveType() {
        final var boxer = new IntBoxer(methodResolver);
        final var expression = mock(ExpressionTree.class);
        final var declaredType = mock(TypeMirror.class);
        when(declaredType.getKind()).thenReturn(TypeKind.DECLARED);

        final var result = boxer.boxer(expression, declaredType);

        assertSame(expression, result);
    }

    @Test
    void intBoxerUnboxesWhenTargetIsPrimitive() {
        final var methodResolver2 = mock(MethodResolver.class);
        when(methodResolver2.resolveMethod(any(MethodInvocationTree.class), any()))
                .thenReturn(Optional.empty());

        final var boxer = new IntBoxer(methodResolver2);
        final var expression = mock(ExpressionTree.class);
        final PrimitiveType intType = new CPrimitiveType(TypeKind.INT);

        final var result = boxer.boxer(expression, intType);

        assertNotSame(expression, result);
        assertTrue(result instanceof MethodInvocationTree);
    }

    @Test
    void longBoxerUnboxesWhenTargetIsPrimitive() {
        final var methodResolver2 = mock(MethodResolver.class);
        when(methodResolver2.resolveMethod(any(MethodInvocationTree.class), any()))
                .thenReturn(Optional.empty());

        final var boxer = new LongBoxer(methodResolver2);
        final var expression = mock(ExpressionTree.class);
        final PrimitiveType longType = new CPrimitiveType(TypeKind.LONG);

        final var result = boxer.boxer(expression, longType);

        assertNotSame(expression, result);
        assertTrue(result instanceof MethodInvocationTree);
    }

    @Test
    void shortBoxerUnboxesWhenTargetIsPrimitive() {
        final var methodResolver2 = mock(MethodResolver.class);
        when(methodResolver2.resolveMethod(any(MethodInvocationTree.class), any()))
                .thenReturn(Optional.empty());

        final var boxer = new ShortBoxer(methodResolver2);
        final var expression = mock(ExpressionTree.class);
        final PrimitiveType shortType = new CPrimitiveType(TypeKind.SHORT);

        final var result = boxer.boxer(expression, shortType);

        assertNotSame(expression, result);
        assertTrue(result instanceof MethodInvocationTree);
    }

    @Test
    void byteBoxerUnboxesWhenTargetIsPrimitive() {
        final var methodResolver2 = mock(MethodResolver.class);
        when(methodResolver2.resolveMethod(any(MethodInvocationTree.class), any()))
                .thenReturn(Optional.empty());

        final var boxer = new ByteBoxer(methodResolver2);
        final var expression = mock(ExpressionTree.class);
        final PrimitiveType byteType = new CPrimitiveType(TypeKind.BYTE);

        final var result = boxer.boxer(expression, byteType);

        assertNotSame(expression, result);
        assertTrue(result instanceof MethodInvocationTree);
    }

    @Test
    void longBoxerReturnsExpressionUnchangedForNonPrimitiveType() {
        final var boxer = new LongBoxer(methodResolver);
        final var expression = mock(ExpressionTree.class);
        final var declaredType = mock(TypeMirror.class);
        when(declaredType.getKind()).thenReturn(TypeKind.DECLARED);

        final var result = boxer.boxer(expression, declaredType);

        assertSame(expression, result);
    }

    @Test
    void shortBoxerReturnsExpressionUnchangedForNonPrimitiveType() {
        final var boxer = new ShortBoxer(methodResolver);
        final var expression = mock(ExpressionTree.class);
        final var declaredType = mock(TypeMirror.class);
        when(declaredType.getKind()).thenReturn(TypeKind.DECLARED);

        final var result = boxer.boxer(expression, declaredType);

        assertSame(expression, result);
    }

    @Test
    void byteBoxerReturnsExpressionUnchangedForNonPrimitiveType() {
        final var boxer = new ByteBoxer(methodResolver);
        final var expression = mock(ExpressionTree.class);
        final var declaredType = mock(TypeMirror.class);
        when(declaredType.getKind()).thenReturn(TypeKind.DECLARED);

        final var result = boxer.boxer(expression, declaredType);

        assertSame(expression, result);
    }
}
