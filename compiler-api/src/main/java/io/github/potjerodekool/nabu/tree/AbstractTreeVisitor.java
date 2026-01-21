package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.element.*;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.statement.*;

public abstract class AbstractTreeVisitor<R, P> implements TreeVisitor<R, P>, PatternTreeVisitor<R, P> {

    @Override
    public R visitUnknown(final Tree tree, final P Param) {
        return null;
    }

    public R defaultAnswer(final Tree tree,
                           final P param) {
        return null;
    }

    public R acceptTree(final Tree tree, final P param) {
        return switch (tree) {
            case CompilationUnit compilationUnit -> visitCompilationUnit(compilationUnit, param);
            case PackageDeclaration packageDeclaration -> visitPackageDeclaration(packageDeclaration, param);
            case ImportItem importItem -> visitImportItem(importItem, param);
            case ClassDeclaration classDeclaration -> visitClass(classDeclaration, param);
            case Function function -> visitFunction(function, param);
            case BlockStatementTree blockStatementTree -> visitBlockStatement(blockStatementTree, param);
            case ReturnStatementTree returnStatementTree -> visitReturnStatement(returnStatementTree, param);
            case LambdaExpressionTree lambdaExpressionTree -> visitLambdaExpression(lambdaExpressionTree, param);
            case BinaryExpressionTree binaryExpressionTree -> visitBinaryExpression(binaryExpressionTree, param);
            case FieldAccessExpressionTree fieldAccessExpressionTree -> visitFieldAccessExpression(fieldAccessExpressionTree, param);
            case MethodInvocationTree methodInvocationTree -> visitMethodInvocation(methodInvocationTree, param);
            case ExpressionStatementTree expressionStatementTree -> visitExpressionStatement(expressionStatementTree, param);
            case AnnotatedTypeTree annotatedTypeTree -> visitAnnotatedType(annotatedTypeTree, param);
            case VariableDeclaratorTree variableDeclaratorTree -> visitVariableDeclaratorStatement(variableDeclaratorTree, param);
            case UnaryExpressionTree unaryExpressionTree -> visitUnaryExpression(unaryExpressionTree, param);
            case WildcardExpressionTree wildcardExpressionTree -> visitWildCardExpression(wildcardExpressionTree, param);
            case IfStatementTree ifStatementTree -> visitIfStatement(ifStatementTree, param);
            case ForStatementTree forStatementTree -> visitForStatement(forStatementTree, param);
            case EnhancedForStatementTree enhancedForStatementTree -> visitEnhancedForStatement(enhancedForStatementTree, param);
            case NewClassExpression newClassExpression -> visitNewClass(newClassExpression, param);
            case WhileStatementTree whileStatementTree -> visitWhileStatement(whileStatementTree, param);
            case DoWhileStatementTree doWhileStatementTree -> visitDoWhileStatement(doWhileStatementTree, param);
            case AnnotationTree annotationTree -> visitAnnotation(annotationTree, param);
            case AssignmentExpressionTree assignmentExpressionTree -> visitAssignment(assignmentExpressionTree, param);
            case CastExpressionTree castExpressionTree -> visitCastExpression(castExpressionTree, param);
            case SwitchStatement switchStatement -> visitSwitchStatement(switchStatement, param);
            case CaseStatement caseStatement -> visitCaseStatement(caseStatement, param);
            case ConstantCaseLabel constantCaseLabel -> visitConstantCaseLabel(constantCaseLabel, param);
            case ThrowStatement throwStatement -> visitThrowStatement(throwStatement, param);
            case IdentifierTree identifierTree -> visitIdentifier(identifierTree, param);
            case PrimitiveTypeTree primitiveTypeTree -> visitPrimitiveType(primitiveTypeTree, param);
            case LiteralExpressionTree literalExpressionTree -> visitLiteralExpression(literalExpressionTree, param);
            case VariableTypeTree variableTypeTree -> visitVariableType(variableTypeTree, param);
            case NewArrayExpression newArrayExpression -> visitNewArray(newArrayExpression, param);
            case ArrayTypeTree arrayTypeTree -> visitArrayType(arrayTypeTree, param);
            case TypeApplyTree typeApplyTree -> visitTypeIdentifier(typeApplyTree, param);
            case InstanceOfExpression instanceOfExpression -> visitInstanceOfExpression(instanceOfExpression, param);
            case IntersectionTypeTree intersectionTypeTree -> visitIntersectionType(intersectionTypeTree, param);
            case TypeParameterTree typeParameterTree -> visitTypeParameter(typeParameterTree, param);
            case ArrayAccessExpressionTree arrayAccessExpressionTree -> visitArrayAccess(arrayAccessExpressionTree, param);
            case PatternCaseLabel patternCaseLabel -> visitPatternCaseLabel(patternCaseLabel, param);
            case MemberReference memberReference -> visitMemberReference(memberReference, param);
            case BreakStatement breakStatement -> visitBreakStatement(breakStatement, param);
            case CatchTree catchTree -> visitCatch(catchTree, param);
            case ModuleDeclaration moduleDeclaration -> visitModuleDeclaration(moduleDeclaration, param);
            case TypePattern typePattern -> visitTypePattern(typePattern, param);
            case RequiresTree requiresTree -> visitRequires(requiresTree, param);
            case ExportsTree expressionTree -> visitExports(expressionTree, param);
            case OpensTree opensTree -> visitOpens(opensTree, param);
            case UsesTree usesTree -> visitUses(usesTree, param);
            case ProvidesTree providesTree -> visitProvides(providesTree, param);
            case TryStatementTree tryStatementTree -> visitTryStatement(tryStatementTree, param);
            case AssertStatement assertStatement -> visitAssertStatement(assertStatement, param);
            case YieldStatement yieldStatement -> visitYieldStatement(yieldStatement, param);
            case LabeledStatement labeledStatement -> visitLabeledStatement(labeledStatement, param);
            case SynchronizedStatement synchronizedStatement -> visitSynchronizedStatement(synchronizedStatement, param);
            case ContinueStatement continueStatement -> visitContinueStatement(continueStatement, param);
            /*
               We could add support for other tree types that are specific to a language.
               We could add that via extension points so a language plugin can implement
               extensions to support those tree types.
             */
            default -> throw new TodoException(tree.getClass().getName()); //TODO add missing cases.
        };
    }

    @Override
    public R visitCompilationUnit(final CompilationUnit compilationUnit,
                                  final P param) {
        if (compilationUnit.getPackageDeclaration() != null) {
            acceptTree(compilationUnit.getPackageDeclaration(), param);
        }

        compilationUnit.getImportItems().forEach(importItem ->
                acceptTree(importItem, param)
        );

        compilationUnit.getClasses().forEach(classDeclaration ->
            acceptTree(classDeclaration, param)
        );
        return defaultAnswer(compilationUnit, param);
    }

    @Override
    public R visitImportItem(final ImportItem importItem, final P param) {
        return defaultAnswer(importItem, param);
    }

    @Override
    public R visitFunction(final Function function, final P param) {
        function.getModifiers().getAnnotations().forEach(annotationTree ->
                acceptTree(annotationTree, param));

        function.getParameters().forEach(parameter ->
                acceptTree(parameter, param)
        );
        final var returnType = function.getReturnType();

        if (returnType != null) {
            acceptTree(returnType, param);
        }

        function.getThrownTypes().forEach(thrownType ->
                acceptTree(thrownType, param));

        final var body = function.getBody();

        if (body != null) {
            acceptTree(body, param);
        }

        return defaultAnswer(function, param);
    }

    @Override
    public R visitBlockStatement(final BlockStatementTree blockStatement, final P param) {
        blockStatement.getStatements()
                .forEach(statementTree ->
                        acceptTree(statementTree, param));
        return defaultAnswer(blockStatement, param);
    }

    @Override
    public R visitReturnStatement(final ReturnStatementTree returnStatement, final P param) {
        final var expression = returnStatement.getExpression();

        if (expression != null) {
            return acceptTree(
                    returnStatement.getExpression(),
                    param
            );
        } else {
            return defaultAnswer(returnStatement, param);
        }
    }

    @Override
    public R visitLambdaExpression(final LambdaExpressionTree lambdaExpression, final P param) {
        lambdaExpression.getVariables().forEach(variable ->
                acceptTree(variable, param));
        acceptTree(lambdaExpression.getBody(), param);
        return defaultAnswer(lambdaExpression, param);
    }

    @Override
    public R visitBinaryExpression(final BinaryExpressionTree binaryExpression, final P param) {
        acceptTree(binaryExpression.getLeft(), param);
        acceptTree(binaryExpression.getRight(), param);
        return defaultAnswer(binaryExpression, param);
    }

    @Override
    public R visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression, final P param) {
        acceptTree(fieldAccessExpression.getSelected(), param);
        acceptTree(fieldAccessExpression.getField(), param);
        return defaultAnswer(fieldAccessExpression, param);
    }

    @Override
    public R visitClass(final ClassDeclaration classDeclaration, final P param) {
        classDeclaration.getModifiers().getAnnotations()
                        .forEach(annotation -> acceptTree(annotation, param));
        classDeclaration.getEnclosedElements().forEach(enclosedElement ->
                acceptTree(enclosedElement, param));
        return defaultAnswer(classDeclaration, param);
    }

    @Override
    public R visitMethodInvocation(final MethodInvocationTree methodInvocation, final P param) {
        methodInvocation.getTypeArguments()
                        .forEach(typeArg -> acceptTree(typeArg, param));
        acceptTree(methodInvocation.getMethodSelector(), param);
methodInvocation.getArguments().forEach(arg -> acceptTree(arg, param));
        return defaultAnswer(methodInvocation, param);
    }

    @Override
    public R visitExpressionStatement(final ExpressionStatementTree expressionStatement, final P param) {
        acceptTree(expressionStatement.getExpression(), param);
        return defaultAnswer(expressionStatement, param);
    }

    @Override
    public R visitAnnotatedType(final AnnotatedTypeTree annotatedType, final P param) {
        annotatedType.getAnnotations().forEach(a ->
                acceptTree(a, param));
        acceptTree(annotatedType.getClazz(), param);

        annotatedType.getArguments().forEach(a ->
                acceptTree(a, param));
        return defaultAnswer(annotatedType, param);
    }

    @Override
    public R visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final P param) {
        acceptTree(variableDeclaratorStatement.getName(), param);

        acceptTree(variableDeclaratorStatement.getName(), param);

        if (variableDeclaratorStatement.getValue() != null) {
            acceptTree(
                    variableDeclaratorStatement.getValue(),
                    param
            );
        }

        acceptTree(variableDeclaratorStatement.getVariableType(), param);

        variableDeclaratorStatement.getAnnotations().forEach(a ->
                acceptTree(a, param));

        return defaultAnswer(variableDeclaratorStatement, param);
    }

    @Override
    public R visitUnaryExpression(final UnaryExpressionTree unaryExpression, final P param) {
        acceptTree(unaryExpression.getExpression(), param);
        return defaultAnswer(unaryExpression, param);
    }

    @Override
    public R visitWildCardExpression(final WildcardExpressionTree wildCardExpression, final P param) {
        if (wildCardExpression.getBound() != null) {
            acceptTree(wildCardExpression.getBound(), param);
        }
        return defaultAnswer(wildCardExpression, param);
    }

    @Override
    public R visitIfStatement(final IfStatementTree ifStatementTree, final P param) {
        acceptTree(ifStatementTree.getExpression(), param);
        acceptTree(ifStatementTree.getThenStatement(), param);

        if (ifStatementTree.getElseStatement() != null) {
            acceptTree(ifStatementTree.getElseStatement(), param);
        }

        return defaultAnswer(ifStatementTree, param);
    }

    @Override
    public R visitForStatement(final ForStatementTree forStatement, final P param) {
        forStatement.getForInit().forEach(it -> acceptTree(it, param));
        accept(forStatement.getCondition(), param);
        forStatement.getForUpdate().forEach(it ->
                acceptTree(it, param));
        acceptTree(forStatement.getStatement(), param);
        return defaultAnswer(forStatement, param);
    }

    @Override
    public R visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement, final P param) {
        acceptTree(enhancedForStatement.getExpression(), param);
        acceptTree(enhancedForStatement.getLocalVariable(), param);
        acceptTree(enhancedForStatement.getStatement(), param);
        return defaultAnswer(enhancedForStatement, param);
    }

    private void accept(final Tree tree,
                        final P param) {
        if (tree != null) {
            acceptTree(tree, param);
        }
    }

    @Override
    public R visitNewClass(final NewClassExpression newClassExpression, final P param) {
        acceptTree(newClassExpression.getName(), param);

        if (newClassExpression.getClassDeclaration() != null) {
            acceptTree(newClassExpression.getClassDeclaration(), param);
        }

        newClassExpression.getArguments().forEach(arg ->
                acceptTree(arg, param));
        return defaultAnswer(newClassExpression, param);
    }

    @Override
    public R visitWhileStatement(final WhileStatementTree whileStatement, final P param) {
        acceptTree(whileStatement.getCondition(), param);
        acceptTree(whileStatement.getBody(), param);
        return defaultAnswer(whileStatement, param);
    }

    @Override
    public R visitDoWhileStatement(final DoWhileStatementTree doWhileStatement, final P param) {
        acceptTree(doWhileStatement.getBody(), param);
        acceptTree(doWhileStatement.getCondition(), param);
        return defaultAnswer(doWhileStatement, param);
    }

    @Override
    public R visitAnnotation(final AnnotationTree annotationTree, final P param) {
        acceptTree(annotationTree.getName(), param);
        annotationTree.getArguments().forEach(arg ->
                acceptTree(arg, param));
        return defaultAnswer(annotationTree, param);
    }

    @Override
    public R visitAssignment(final AssignmentExpressionTree assignmentExpressionTree, final P param) {
        acceptTree(assignmentExpressionTree.getLeft(), param);
        acceptTree(assignmentExpressionTree.getRight(), param);
        return defaultAnswer(assignmentExpressionTree, param);
    }

    @Override
    public R visitCastExpression(final CastExpressionTree castExpressionTree, final P param) {
        acceptTree(castExpressionTree.getTargetType(), param);
        acceptTree(castExpressionTree.getExpression(), param);
        return defaultAnswer(castExpressionTree, param);
    }

    @Override
    public R visitSwitchStatement(final SwitchStatement switchStatement, final P param) {
        acceptTree(switchStatement.getSelector(), param);

        switchStatement.getCases().forEach(caseStatement ->
                acceptTree(caseStatement, param));
        return defaultAnswer(switchStatement, param);
    }

    @Override
    public R visitCaseStatement(final CaseStatement caseStatement, final P param) {
        caseStatement.getLabels().forEach(label ->
                acceptTree(label, param));
        acceptTree(caseStatement.getBody(), param);
        return defaultAnswer(caseStatement, param);
    }

    @Override
    public R visitConstantCaseLabel(final ConstantCaseLabel constantCaseLabel, final P param) {
        acceptTree(constantCaseLabel.getExpression(), param);
        return defaultAnswer(constantCaseLabel, param);
    }

    @Override
    public R visitThrowStatement(final ThrowStatement throwStatement, final P param) {
        acceptTree(throwStatement.getExpression(), param);
        return TreeVisitor.super.visitThrowStatement(throwStatement, param);
    }
}
