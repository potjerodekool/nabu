package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.CVariableDeclaratorStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CAnnotatedType;
import io.github.potjerodekool.nabu.compiler.tree.expression.CPrimitiveType;
import io.github.potjerodekool.nabu.compiler.tree.expression.CTypeApply;

public abstract class AbstractTreeVisitor<R, P> implements TreeVisitor<R, P> {

    @Override
    public R visitCompilationUnit(final CompilationUnit compilationUnit,
                                  final P param) {
        if (compilationUnit.getPackageDeclaration() != null) {
            compilationUnit.getPackageDeclaration().accept(this, param);
        }

        compilationUnit.getImportItems().forEach(importItem ->
                importItem.accept(this, param)
        );

        compilationUnit.getClasses().forEach(e -> e.accept(this, param));
        return null;
    }

    @Override
    public R visitImportItem(final ImportItem importItem, final P param) {
        return null;
    }

    @Override
    public R visitFunction(final CFunction function, final P param) {
        function.getParameters().forEach(fp -> fp.accept(this, param));
        final var returnType = function.getReturnType();

        if (returnType != null) {
            returnType.accept(this, param);
        }

        final var body = function.getBody();

        if (body != null) {
            body.accept(this, param);
        }

        return null;
    }

    @Override
    public R visitVariable(final CVariable variable, final P param) {
        return null;
    }

    @Override
    public R visitBlockStatement(final BlockStatement blockStatement, final P param) {
        blockStatement.getStatements().forEach(s -> s.accept(this, param));
        return null;
    }

    @Override
    public R visitReturnStatement(final ReturnStatement returnStatement, final P param) {
        final var expression = returnStatement.getExpression();

        if (expression != null) {
            return returnStatement.getExpression().accept(this, param);
        } else {
            return null;
        }
    }

    @Override
    public R visitIdentifier(final CIdent ident, final P param) {
        return null;
    }

    @Override
    public R visitLambdaExpression(final CLambdaExpression lambdaExpression, final P param) {
        lambdaExpression.getVariables().forEach(variable -> variable.accept(this, param));
        lambdaExpression.getBody().accept(this, param);
        return null;
    }

    @Override
    public R visitBinaryExpression(final BinaryExpression binaryExpression, final P param) {
        binaryExpression.getLeft().accept(this, param);
        binaryExpression.getRight().accept(this, param);
        return null;
    }

    @Override
    public R visitFieldAccessExpression(final CFieldAccessExpression fieldAccessExpression, final P param) {
        fieldAccessExpression.getTarget().accept(this, param);
        fieldAccessExpression.getField().accept(this, param);
        return null;
    }

    @Override
    public R visitClass(final CClassDeclaration classDeclaration, final P param) {
        classDeclaration.getEnclosedElements().forEach(e -> e.accept(this, param));
        return null;
    }

    @Override
    public R visitTypeIdentifier(final CTypeApply typeIdentifier, final P param) {
        return null;
    }

    @Override
    public R visitMethodInvocation(final MethodInvocation methodInvocation, final P param) {
        final var target = methodInvocation.getTarget();
        if (target != null) {
            target.accept(this, param);
        }
        methodInvocation.getName().accept(this, param);
        methodInvocation.getArguments().forEach(arg -> arg.accept(this, param));
        return null;
    }

    @Override
    public R visitLiteralExpression(final LiteralExpression literalExpression, final P param) {
        return null;
    }

    @Override
    public R visitStatementExpression(final StatementExpression statementExpression, final P param) {
        return statementExpression.getExpression().accept(this, param);
    }

    @Override
    public R visitAnnotatedType(final CAnnotatedType annotatedType, final P param) {
        annotatedType.getClazz().accept(this, param);
        annotatedType.getArguments().forEach(a -> a.accept(this, param));
        return null;
    }

    @Override
    public R visitTypeNameExpression(final CTypeNameExpression typeNameExpression, final P param) {
        return null;
    }

    @Override
    public R visitNoTypeExpression(final CNoTypeExpression noTypeExpression, final P param) {
        return null;
    }

    @Override
    public R visitVariableDeclaratorStatement(final CVariableDeclaratorStatement variableDeclaratorStatement, final P param) {
        variableDeclaratorStatement.getType().accept(this, param);
        variableDeclaratorStatement.getIdent().accept(this, param);
        variableDeclaratorStatement.getValue().accept(this, param);
        return null;
    }

    @Override
    public R visitPackageDeclaration(final CPackageDeclaration packageDeclaration, final P param) {
        return null;
    }

    @Override
    public R visitPrimitiveType(final CPrimitiveType primitiveType, final P param) {
        return null;
    }

    @Override
    public R visitUnaryExpression(final UnaryExpression unaryExpression, final P param) {
        unaryExpression.getExpression().accept(this, param);
        return null;
    }

    @Override
    public R visitVariableType(final CVariableType variableType, final P param) {
        return null;
    }

    @Override
    public R visitAsExpression(final AsExpression asExpression, final P param) {
        return null;
    }
}
