package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Variable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;

public interface TreeVisitor<R, P> {

    R visitCompilationUnit(CompilationUnit compilationUnit, P param);

    R visitFunction(Function function, P param);

    R visitVariable(Variable variable, P param);

    R visitBlockStatement(BlockStatement blockStatement, P param);

    R visitReturnStatement(ReturnStatement returnStatement, P param);

    R visitIdentifier(IdentifierTree identifier, P param);

    R visitLambdaExpression(LambdaExpressionTree lambdaExpression, P param);

    R visitBinaryExpression(BinaryExpressionTree binaryExpression, P param);

    R visitFieldAccessExpression(FieldAccessExpressioTree fieldAccessExpression, P param);

    R visitClass(ClassDeclaration classDeclaration, P param);

    R visitMethodInvocation(MethodInvocationTree methodInvocation, P param);

    R visitLiteralExpression(LiteralExpressionTree literalExpression, P param);

    R visitStatementExpression(StatementExpression statementExpression, P param);

    R visitVariableDeclaratorStatement(CVariableDeclaratorStatement variableDeclaratorStatement, P param);

    R visitPackageDeclaration(PackageDeclaration packageDeclaration, P param);

    R visitImportItem(ImportItem importItem, P param);

    R visitPrimitiveType(PrimitiveTypeTree primitiveType, P param);

    R visitUnaryExpression(UnaryExpressionTree unaryExpression, P param);

    R visitTypeIdentifier(TypeApplyTree typeIdentifier, P param);

    R visitAnnotatedType(AnnotatedTypeTree annotatedType, P param);

    R visitTypeNameExpression(TypeNameExpressioTree typeNameExpression, P param);

    R visitVariableType(VariableTypeTree variableType, P param);

    R visitCastExpression(CastExpressionTree castExpressionTree, P param);

    R visitWildCardExpression(WildCardExpressionTree wildCardExpression, P param);

    R visitIfStatement(IfStatementTree ifStatementTree, P param);

    R visitEmptyStatement(EmptyStatementTree emptyStatementTree, P param);

    R visitForStatement(ForStatement forStatement, P param);

    R visitEnhancedForStatement(EnhancedForStatement enhancedForStatement, P param);

    R visitAnnotation(AnnotationTree annotationTree, P param);

    R visitInstanceOfExpression(InstanceOfExpression instanceOfExpression, P param);

    R visitNewClass(NewClassExpression newClassExpression, P param);

    R visitWhileStatement(WhileStatement whileStatement, P param);

    R visitDoWhileStatement(DoWhileStatement doWhileStatement, P param);

    R visitTypeParameter(TypeParameterTree typeParameterTree, P param);
}
