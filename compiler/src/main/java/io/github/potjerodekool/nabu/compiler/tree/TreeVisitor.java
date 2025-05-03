package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;

public interface TreeVisitor<R, P> {

    R visitUnknown(Tree tree, P Param);

    default R visitCompilationUnit(CompilationUnit compilationUnit, P param) {
        return visitUnknown(compilationUnit, param);
    }

    default R visitFunction(Function function, P param) {
        return visitUnknown(function, param);
    }

    default R visitBlockStatement(BlockStatementTree blockStatement, P param) {
        return visitUnknown(blockStatement, param);
    }

    default R visitReturnStatement(ReturnStatementTree returnStatement, P param) {
        return visitUnknown(returnStatement, param);
    }

    default R visitIdentifier(IdentifierTree identifier, P param) {
        return visitUnknown(identifier, param);
    }

    default R visitLambdaExpression(LambdaExpressionTree lambdaExpression, P param) {
        return visitUnknown(lambdaExpression, param);
    }

    default R visitBinaryExpression(BinaryExpressionTree binaryExpression, P param) {
        return visitUnknown(binaryExpression, param);
    }

    default R visitFieldAccessExpression(FieldAccessExpressionTree fieldAccessExpression, P param) {
        return visitUnknown(fieldAccessExpression, param);
    }

    default R visitClass(ClassDeclaration classDeclaration, P param) {
        return visitUnknown(classDeclaration, param);
    }

    default R visitMethodInvocation(MethodInvocationTree methodInvocation, P param) {
        return visitUnknown(methodInvocation, param);
    }

    default R visitLiteralExpression(LiteralExpressionTree literalExpression, P param) {
        return visitUnknown(literalExpression, param);
    }

    default R visitExpressionStatement(ExpressionStatementTree expressionStatement, P param) {
        return visitUnknown(expressionStatement, param);
    }

    default R visitVariableDeclaratorStatement(VariableDeclaratorTree variableDeclaratorStatement, P param) {
        return visitUnknown(variableDeclaratorStatement, param);
    }

    default R visitPackageDeclaration(PackageDeclaration packageDeclaration, P param) {
        return visitUnknown(packageDeclaration, param);
    }

    default R visitImportItem(ImportItem importItem, P param) {
        return visitUnknown(importItem, param);
    }

    default R visitPrimitiveType(PrimitiveTypeTree primitiveType, P param) {
        return visitUnknown(primitiveType, param);
    }

    default R visitUnaryExpression(UnaryExpressionTree unaryExpression, P param) {
        return visitUnknown(unaryExpression, param);
    }

    default R visitTypeIdentifier(TypeApplyTree typeIdentifier, P param) {
        return visitUnknown(typeIdentifier, param);
    }

    default R visitAnnotatedType(AnnotatedTypeTree annotatedType, P param) {
        return visitUnknown(annotatedType, param);
    }

    default R visitTypeNameExpression(TypeNameExpressionTree typeNameExpression, P param) {
        return visitUnknown(typeNameExpression, param);
    }

    default R visitVariableType(VariableTypeTree variableType, P param) {
        return visitUnknown(variableType, param);
    }

    default R visitCastExpression(CastExpressionTree castExpressionTree, P param) {
        return visitUnknown(castExpressionTree, param);
    }

    default R visitWildCardExpression(WildcardExpressionTree wildCardExpression, P param) {
        return visitUnknown(wildCardExpression, param);
    }

    default R visitIfStatement(IfStatementTree ifStatementTree, P param) {
        return visitUnknown(ifStatementTree, param);
    }

    default R visitEmptyStatement(EmptyStatementTree emptyStatementTree, P param) {
        return visitUnknown(emptyStatementTree, param);
    }

    default R visitForStatement(ForStatementTree forStatement, P param) {
        return visitUnknown(forStatement, param);
    }

    default R visitEnhancedForStatement(EnhancedForStatementTree enhancedForStatement, P param) {
        return visitUnknown(enhancedForStatement, param);
    }

    default R visitAnnotation(AnnotationTree annotationTree, P param) {
        return visitUnknown(annotationTree, param);
    }

    default R visitInstanceOfExpression(InstanceOfExpression instanceOfExpression, P param) {
        return visitUnknown(instanceOfExpression, param);
    }

    default R visitNewClass(NewClassExpression newClassExpression, P param) {
        return visitUnknown(newClassExpression, param);
    }

    default R visitWhileStatement(WhileStatementTree whileStatement, P param) {
        return visitUnknown(whileStatement, param);
    }

    default R visitDoWhileStatement(DoWhileStatementTree doWhileStatement, P param) {
        return visitUnknown(doWhileStatement, param);
    }

    default R visitTypeParameter(TypeParameterTree typeParameterTree, P param) {
        return visitUnknown(typeParameterTree, param);
    }

    default R visitTypeVariable(TypeVariableTree typeVariableTree, P param) {
        return visitUnknown(typeVariableTree, param);
    }

    default R visitArrayType(ArrayTypeTree arrayTypeTree, P param) {
        return visitUnknown(arrayTypeTree, param);
    }

    default R visitAssignment(AssignmentExpressionTree assignmentExpressionTree, P param) {
        return visitUnknown(assignmentExpressionTree, param);
    }

    default R visitNewArray(NewArrayExpression newArrayExpression, P param) {
        return visitUnknown(newArrayExpression, param);
    }

    default R visitErroneous(ErrorTree errorTree, P param) {
        return visitUnknown(errorTree, param);
    }

    default R visitIntersectionType(IntersectionTypeTree intersectionTypeTree, P param) {
        return visitUnknown(intersectionTypeTree, param);
    }

    default R visitArrayAccess(ArrayAccessExpressionTree arrayAccessExpressionTree, P param) {
        return visitUnknown(arrayAccessExpressionTree, param);
    }

    default R visitMemberReference(MemberReference memberReference, P param) {
        return visitUnknown(memberReference, param);
    }

    default R visitTryStatement(TryStatementTree tryStatementTree, P param) {
        return visitUnknown(tryStatementTree, param);
    }

    default R visitCatch(CatchTree catchTree, P param) {
        return visitUnknown(catchTree, param);
    }

    default R visitTypeUnion(TypeUnionExpressionTree typeUnionTreeExpression, P param) {
        return visitUnknown(typeUnionTreeExpression, param);
    }

    default R visitModuleDeclaration(ModuleDeclaration moduleDeclaration, P param) {
        return visitUnknown(moduleDeclaration, param);
    }

    default R visitRequires(RequiresTree requiresTree, P param) {
        return visitUnknown(requiresTree, param);
    }

    default R visitProvides(ProvidesTree providesTree, P param) {
        return visitUnknown(providesTree, param);
    }

    default R visitExports(ExportsTree exportsTree, P param) {
        return visitUnknown(exportsTree, param);
    }

    default R visitUses(UsesTree usesTree, P param) {
        return visitUnknown(usesTree, param);
    }

    default R visitOpens(OpensTree opensTree, P param) {
        return visitUnknown(opensTree, param);
    }

    default R visitLabeledStatement(LabeledStatement labeledStatement, P param) {
        return visitUnknown(labeledStatement, param);
    }

    default R visitBreakStatement(BreakStatement breakStatement, P param) {
        return visitUnknown(breakStatement, param);
    }

    default R visitContinueStatement(ContinueStatement continueStatement, P param) {
        return visitUnknown(continueStatement, param);
    }

    default R visitSynchronizedStatement(SynchronizedStatement synchronizedStatement, P param) {
        return visitUnknown(synchronizedStatement, param);
    }

    default R visitThrowStatement(ThrowStatement throwStatement, P param) {
        return visitUnknown(throwStatement, param);
    }

    default R visitYieldStatement(YieldStatement yieldStatement, P param) {
        return visitUnknown(yieldStatement, param);
    }

    default R visitAssertStatement(AssertStatement assertStatement, P param) {
        return visitUnknown(assertStatement, param);
    }

    default R visitCaseStatement(CaseStatement caseStatement, P param) {
        return visitUnknown(caseStatement, param);
    }

    default R visitConstantCaseLabel(ConstantCaseLabel constantCaseLabel, P param) {
        return visitUnknown(constantCaseLabel, param);
    }

    default R visitDefaultCaseLabel(DefaultCaseLabel defaultCaseLabel, P param) {
        return visitUnknown(defaultCaseLabel, param);
    }

    default R visitSwitchStatement(SwitchStatement switchStatement, P param) {
        return visitUnknown(switchStatement, param);
    }

    default R visitPatternCaseLabel(PatternCaseLabel patternCaseLabel, P param) {
        return visitUnknown(patternCaseLabel, param);
    }

    default R visitBindingPattern(BindingPattern bindingPattern, P param) {
        return visitUnknown(bindingPattern, param);
    }
}
