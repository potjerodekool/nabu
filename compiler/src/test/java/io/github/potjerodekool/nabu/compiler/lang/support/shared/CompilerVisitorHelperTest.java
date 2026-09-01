package io.github.potjerodekool.nabu.compiler.lang.support.shared;

import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.builder.MemberReferenceBuilder;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompilerVisitorHelperTest {

    private ParserRuleContext mockContext(int line, int col) {
        final var token = org.mockito.Mockito.mock(Token.class);
        org.mockito.Mockito.when(token.getLine()).thenReturn(line);
        org.mockito.Mockito.when(token.getCharPositionInLine()).thenReturn(col);
        final var ctx = org.mockito.Mockito.mock(ParserRuleContext.class);
        org.mockito.Mockito.when(ctx.getStart()).thenReturn(token);
        return ctx;
    }

    @Test
    void isStringLiteralReturnsTrueForStringLiteral() {
        final var literal = TreeMaker.literalExpressionTree("hello", 1, 1);
        literal.setType(null);
        assertTrue(CompilerVisitorHelper.isStringLiteral(literal));
    }

    @Test
    void isStringLiteralReturnsFalseForIntLiteral() {
        final var literal = TreeMaker.literalExpressionTree(42, 1, 1);
        literal.setType(null);
        assertFalse(CompilerVisitorHelper.isStringLiteral(literal));
    }

    @Test
    void isStringLiteralReturnsFalseForNull() {
        assertFalse(CompilerVisitorHelper.isStringLiteral(null));
    }

    @Test
    void mergeLiteralsConcatenatesValues() {
        final var left = TreeMaker.literalExpressionTree("Hello", 1, 1);
        left.setType(null);
        final var right = TreeMaker.literalExpressionTree(" World", 1, 10);
        right.setType(null);

        final var merged = CompilerVisitorHelper.mergeLiterals(left, right);

        assertNotNull(merged);
        assertEquals("Hello World", merged.getLiteral().toString());
    }

    @Test
    void toLambdaVariableWrapsIdentifierAsParameter() {
        final var identifier = TreeMaker.identifier("x", 1, 1);

        final var result = CompilerVisitorHelper.toLambdaVariable(identifier);

        assertNotNull(result);
        assertEquals(io.github.potjerodekool.nabu.tree.element.Kind.PARAMETER, result.getKind());
        assertEquals("x", result.getName().getName());
    }

    @Test
    void createMemberReferenceWithNewUsesInitMode() {
        final var ctx = mockContext(5, 10);
        final var identifier = TreeMaker.identifier("new", 5, 10);

        final var result = CompilerVisitorHelper.createMemberReference(
                java.util.List.of(),
                identifier,
                ctx
        );

        assertNotNull(result);
        assertEquals("<init>", result.getExpression().toString());
    }

    @Test
    void createMemberReferenceWithMethodUsesInvokeMode() {
        final var ctx = mockContext(5, 10);
        final var identifier = TreeMaker.identifier("getValue", 5, 10);

        final var result = CompilerVisitorHelper.createMemberReference(
                java.util.List.of(),
                identifier,
                ctx
        );

        assertNotNull(result);
        assertEquals("getValue", result.getExpression().toString());
    }
}
