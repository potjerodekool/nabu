package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.CVariableDeclaratorStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CPrimitiveType;

public interface TreeVisitor<R, P> {

    R visitCompilationUnit(CompilationUnit compilationUnit, P param);

    R visitFunction(CFunction function, P param);

    R visitVariable(CVariable variable, P param);

    R visitBlockStatement(BlockStatement blockStatement, P param);

    R visitReturnStatement(ReturnStatement returnStatement, P param);

    R visitIdentifier(CIdent ident, P param);

    R visitLambdaExpression(CLambdaExpression lambdaExpression, P param);

    R visitBinaryExpression(BinaryExpression binaryExpression, P param);

    R visitFieldAccessExpression(CFieldAccessExpression fieldAccessExpression, P param);

    R visitClass(CClassDeclaration classDeclaration, P param);

    R visitMethodInvocation(MethodInvocation methodInvocation, P param);

    R visitLiteralExpression(LiteralExpression literalExpression, P param);

    R visitStatementExpression(StatementExpression statementExpression, P param);

    R visitVariableDeclaratorStatement(CVariableDeclaratorStatement variableDeclaratorStatement, P param);

    R visitPackageDeclaration(CPackageDeclaration packageDeclaration, P param);

    R visitImportItem(ImportItem importItem, P param);

    R visitPrimitiveType(CPrimitiveType primitiveType, P param);

    R visitUnaryExpression(UnaryExpression unaryExpression, P param);

    R visitTypeIdentifier(CTypeApply typeIdentifier, P param);

    R visitAnnotatedType(CAnnotatedType annotatedType, P param);

    R visitTypeNameExpression(CTypeNameExpression typeNameExpression, P param);

    R visitNoTypeExpression(CNoTypeExpression noTypeExpression, P param);

    R visitVariableType(CVariableType variableType, P param);

    R visitAsExpression(AsExpression asExpression, P param);
}
