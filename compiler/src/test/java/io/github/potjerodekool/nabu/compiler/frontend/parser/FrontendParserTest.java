package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.type.BoundKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrontendParserTest {

    @Test
    void wildcardBoundAccessors() {
        var expr = IdentifierTree.create("String");
        var bound = new WildcardBound(BoundKind.EXTENDS, expr);
        assertEquals(BoundKind.EXTENDS, bound.kind());
        assertSame(expr, bound.expression());
    }

    @Test
    void variableArityParameterAccessors() {
        var modifiers = new Modifiers();
        var type = IdentifierTree.create("String");
        var name = IdentifierTree.create("args");
        var param = new VariableArityParameter(modifiers, type, name);
        assertSame(modifiers, param.modifiers());
        assertSame(type, param.type());
        assertSame(name, param.name());
    }

    @Test
    void processImportExpressionSingleName() {
        var result = SourceVisitor.processImportExpression(IdentifierTree.create("foo"));
        assertInstanceOf(FieldAccessExpressionTree.class, result);
        assertEquals("foo", result.getField().getName());
    }

    @Test
    void processImportExpressionMultiName() {
        var result = SourceVisitor.processImportExpression(IdentifierTree.create("java.lang.String"));
        assertInstanceOf(FieldAccessExpressionTree.class, result);
        assertEquals("String", result.getField().getName());
        var selected = (FieldAccessExpressionTree) result.getSelected();
        assertEquals("lang", selected.getField().getName());
        var inner = selected.getSelected();
        assertInstanceOf(IdentifierTree.class, inner);
        assertEquals("java", ((IdentifierTree) inner).getName());
    }

    @Test
    void processImportExpressionNonIdentifier() {
        var fieldAccess = FieldAccessExpressionTree.create(
                IdentifierTree.create("a"),
                IdentifierTree.create("b"));
        assertSame(fieldAccess, SourceVisitor.processImportExpression(fieldAccess));
    }
}