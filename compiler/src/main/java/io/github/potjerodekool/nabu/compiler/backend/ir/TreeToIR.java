package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.expression.BinaryExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CIdent;
import io.github.potjerodekool.nabu.compiler.tree.expression.CLambdaExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CFieldAccessExpression;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;

public class TreeToIR extends AbstractTreeVisitor<Object, Object> {

    @Override
    public Object visitFunction(final CFunction function, final Object param) {
        return super.visitFunction(function, param);
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit, final Object param) {
        return super.visitCompilationUnit(compilationUnit, param);
    }

    @Override
    public Object visitReturnStatement(final ReturnStatement returnStatement, final Object param) {
        returnStatement.getExpression().accept(this, param);
        throw new UnsupportedOperationException();
    }

    @Override
    public Object visitIdentifier(final CIdent ident, final Object param) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object visitLambdaExpression(final CLambdaExpression lambdaExpression, final Object param) {
        lambdaExpression.getVariables().forEach(variable -> variable.accept(this, param));
        lambdaExpression.getBody().accept(this, param);
        throw new UnsupportedOperationException();
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpression binaryExpression, final Object param) {
        binaryExpression.getLeft().accept(this, param);
        binaryExpression.getRight().accept(this, param);
        throw new UnsupportedOperationException();
    }

    @Override
    public Object visitFieldAccessExpression(final CFieldAccessExpression fieldAccessExpression, final Object param) {
        fieldAccessExpression.getTarget().accept(this, param);
        fieldAccessExpression.getField().accept(this, param);
        throw new UnsupportedOperationException();
    }

    @Override
    public Object visitClass(final CClassDeclaration classDeclaration, final Object param) {
        return super.visitClass(classDeclaration, param);
    }
}
