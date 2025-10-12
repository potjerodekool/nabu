package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.impl.CFunction;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.impl.*;
import io.github.potjerodekool.nabu.tree.impl.*;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.tree.statement.impl.*;
import io.github.potjerodekool.nabu.type.BoundKind;

import java.util.List;

public final class TreeMaker {

    private TreeMaker() {
    }

    public static IdentifierTree identifier(final String identifier,
                                            final int line,
                                            final int columnNumber) {
        return new CIdentifierTree(identifier, line, columnNumber);
    }

    public static PackageDeclaration packageDeclaration(final List<AnnotationTree> annotations,
                                                        final ExpressionTree identifier,
                                                        final int line,
                                                        final int columnNumber) {
        return new CPackageDeclarationTree(annotations, identifier, line, columnNumber);
    }

    public static ImportItem importItem(final FieldAccessExpressionTree qualified,
                                        final boolean isStatic,
                                        final int lineNumber,
                                        final int columnNumber) {
        return new CImportItemTree(
                qualified,
                isStatic,
                lineNumber,
                columnNumber);
    }

    public static VariableDeclaratorTree variableDeclarator(final Kind kind,
                                                            final Modifiers modifiers,
                                                            final ExpressionTree type,
                                                            final IdentifierTree name,
                                                            final ExpressionTree nameExpression,
                                                            final Tree value,
                                                            final int lineNumber,
                                                            final int columnNumber) {
        return new CVariableDeclaratorTree(
                kind,
                modifiers,
                type,
                name,
                nameExpression,
                value,
                lineNumber,
                columnNumber
        );
    }

    public static ReturnStatementTree returnStatement(final ExpressionTree expression,
                                                      final int lineNumber,
                                                      final int columnNumber) {
        return new CReturnStatementTree(expression, lineNumber, columnNumber);
    }


    public static LambdaExpressionTree lambdaExpressionTree(final List<VariableDeclaratorTree> parameters,
                                                            final StatementTree body,
                                                            final int lineNumber,
                                                            final int columnNumber) {
        return new CLambdaExpressionTree(
                parameters,
                body,
                lineNumber,
                columnNumber
        );
    }

    public static ExpressionStatementTree expressionStatement(final ExpressionTree expression,
                                                              final int line,
                                                              final int columnNumber) {
        return new CExpressionStatementTree(expression, line, columnNumber);
    }

    public static BinaryExpressionTree binaryExpressionTree(final ExpressionTree left,
                                                            final Tag tag,
                                                            final ExpressionTree right,
                                                            final int lineNumber,
                                                            final int columnNumber) {
        return new CBinaryExpressionTree(
                left,
                tag,
                right,
                lineNumber,
                columnNumber
        );
    }

    public static InstanceOfExpression instanceOfExpression(final ExpressionTree expression,
                                                            final Tree typeExpression,
                                                            final int lineNumber,
                                                            final int columnNumber) {
        return new CInstanceOfExpression(
                expression,
                typeExpression,
                lineNumber,
                columnNumber
        );
    }

    public static UnaryExpressionTree unaryExpressionTree(final Tag tag,
                                                          final ExpressionTree expression,
                                                          final int lineNumber,
                                                          final int columnNumber) {
        return new CUnaryExpressionTree(
                tag,
                expression,
                lineNumber,
                columnNumber
        );
    }

    public static CastExpressionTree castExpressionTree(final ExpressionTree targetType,
                                                        final ExpressionTree expression,
                                                        final int lineNumber,
                                                        final int columnNumber) {
        return new CCastExpressionTree(targetType, expression, lineNumber, columnNumber);
    }

    public static FieldAccessExpressionTree fieldAccessExpressionTree(final ExpressionTree target,
                                                                      final ExpressionTree field,
                                                                      final int lineNumber,
                                                                      final int columnNumber) {
        return new CFieldAccessExpressionTree(target, field, lineNumber, columnNumber);
    }

    public static MethodInvocationTree methodInvocationTree(final ExpressionTree methodSelector,
                                                            final List<IdentifierTree> typeArguments,
                                                            final List<ExpressionTree> arguments,
                                                            final int lineNumber,
                                                            final int columnNumber) {
        return new CMethodInvocationTree(
                methodSelector,
                typeArguments,
                arguments,
                lineNumber,
                columnNumber
        );
    }

    public static PrimitiveTypeTree primitiveTypeTree(final PrimitiveTypeTree.Kind kind,
                                                      final int lineNumber,
                                                      final int columnNumber) {
        return new CPrimitiveTypeTree(kind, lineNumber, columnNumber);
    }

    public static TypeApplyTree typeApplyTree(final ExpressionTree clazz,
                                              final List<? extends ExpressionTree> typeParameters,
                                              final int lineNumber,
                                              final int columnNumber) {
        return new CTypeApplyTree(clazz, typeParameters, lineNumber, columnNumber);
    }

    public static VariableTypeTree variableTypeTree(final int lineNumber,
                                                    final int columnNumber) {
        return new CVariableTypeTree(
                lineNumber,
                columnNumber
        );
    }

    public static LiteralExpressionTree literalExpressionTree(final Object literal,
                                                              final int lineNumber,
                                                              final int columnNumber) {
        return new CLiteralExpressionTree(literal, lineNumber, columnNumber);
    }

    public static FieldAccessExpressionTree classLiteralTree(final ExpressionTree type,
                                                             final int lineNumber,
                                                             final int columnNumber) {
        return TreeMaker.fieldAccessExpressionTree(
                type,
                TreeMaker.identifier(
                        "class",
                        lineNumber,
                        columnNumber
                ),
                lineNumber,
                columnNumber
        );
    }

    public static WildcardExpressionTree wildcardExpressionTree(final BoundKind boundKind,
                                                                final ExpressionTree bound,
                                                                final int lineNumber,
                                                                final int columnNumber) {
        return new CWildcardExpressionTree(
                boundKind,
                bound,
                lineNumber,
                columnNumber
        );
    }

    public static IfStatementTree ifStatementTree(final ExpressionTree expression,
                                                  final StatementTree thenStatement,
                                                  final StatementTree elseStatement,
                                                  final int lineNumber,
                                                  final int columnNumber) {
        return new CIfStatementTree(
                expression,
                thenStatement,
                elseStatement,
                lineNumber,
                columnNumber
        );
    }

    public static BlockStatementTree blockStatement(final List<StatementTree> statements,
                                                    final int lineNumber,
                                                    final int columnNumber) {
        return new CBlockStatementTree(statements, lineNumber, columnNumber);
    }

    public static EmptyStatementTree emptyStatementTree(final int lineNumber,
                                                        final int columnNumber) {
        return new CEmptyStatementTree(lineNumber, columnNumber);
    }

    public static ForStatementTree forStatement(final List<StatementTree> forInit,
                                                final ExpressionTree expression,
                                                final List<StatementTree> forUpdate,
                                                final StatementTree statement,
                                                final int lineNumber,
                                                final int columnNumber) {
        return new CForStatementTree(
                forInit,
                expression,
                forUpdate,
                statement,
                lineNumber,
                columnNumber
        );
    }

    public static AnnotatedTypeTree annotatedTypeTree(final List<AnnotationTree> annotations,
                                                      final ExpressionTree clazz,
                                                      final List<ExpressionTree> arguments,
                                                      final int lineNumber,
                                                      final int columnNumber) {
        return new CAnnotatedTypeTree(
                annotations,
                clazz,
                arguments,
                lineNumber,
                columnNumber
        );
    }

    public static EnhancedForStatementTree enhancedForStatement(final VariableDeclaratorTree localVariable,
                                                                final ExpressionTree expression,
                                                                final StatementTree statement,
                                                                final int lineNumber,
                                                                final int columnNumber) {
        return new CEnhancedForStatementTree(
                localVariable,
                expression,
                statement,
                lineNumber,
                columnNumber
        );
    }

    public static AnnotationTree annotationTree(final IdentifierTree name,
                                                final List<ExpressionTree> arguments,
                                                final int lineNumber,
                                                final int columnNumber) {
        return new CAnnotationTree(
                name,
                arguments,
                lineNumber,
                columnNumber
        );
    }

    public static NewClassExpression newClassExpression(final ExpressionTree name,
                                                        final List<ExpressionTree> typeArguments,
                                                        final List<ExpressionTree> arguments,
                                                        final ClassDeclaration classDeclaration,
                                                        final int lineNumber,
                                                        final int columnNumber) {
        return new CNewClassExpression(
                name,
                typeArguments,
                arguments,
                classDeclaration,
                lineNumber,
                columnNumber
        );
    }

    public static WhileStatementTree whileStatement(final ExpressionTree condition,
                                                    final StatementTree body,
                                                    final int lineNumber,
                                                    final int columnNumber) {
        return new CWhileStatementTree(
                condition,
                body,
                lineNumber,
                columnNumber
        );
    }

    public static DoWhileStatementTree doWhileStatement(final StatementTree body,
                                                        final ExpressionTree condition,
                                                        final int lineNumber,
                                                        final int columnNumber) {
        return new CDoWhileStatementTree(
                body,
                condition,
                lineNumber,
                columnNumber
        );
    }

    public static AssignmentExpressionTree assignmentExpression(final ExpressionTree left,
                                                                final ExpressionTree right,
                                                                final int lineNumber,
                                                                final int columnNumber) {
        return new CAssignmentExpressionTree(
                left,
                right,
                lineNumber,
                columnNumber
        );
    }

    public static TypeParameterTree typeParameterTree(final List<AnnotationTree> annotations,
                                                      final IdentifierTree identifier,
                                                      final List<ExpressionTree> typeBound,
                                                      final int lineNumber,
                                                      final int columnNumber) {
        return new CTypeParameterTree(
                annotations,
                identifier,
                typeBound,
                lineNumber,
                columnNumber
        );
    }

    public static TypeVariableTree typeVariableTree(final List<AnnotationTree> annotations,
                                                    final IdentifierTree identifier,
                                                    final int lineNumber,
                                                    final int columnNumber) {
        return new CTypeVariableTree(
                annotations,
                identifier,
                lineNumber,
                columnNumber
        );
    }

    public static ArrayTypeTree arrayTypeTree(final Tree componentType,
                                              final List<Dimension> dimensions,
                                              final int lineNumber,
                                              final int columnNumber) {
        return new CArrayTypeTree(
                componentType,
                dimensions,
                lineNumber,
                columnNumber
        );
    }

    public static NewArrayExpression newArrayExpression(final ExpressionTree type,
                                                        final List<ExpressionTree> dimensions,
                                                        final List<ExpressionTree> elements,
                                                        final int lineNumber,
                                                        final int columnNumber) {
        return new CNewArrayExpression(
                type,
                dimensions,
                elements,
                lineNumber,
                columnNumber
        );
    }

    public static CompilationUnit compilationUnit(final FileObject fileObject,
                                                  final List<ImportItem> importItems,
                                                  final List<Tree> declarations,
                                                  final int lineNumber,
                                                  final int columnNumber) {
        return new CCompilationTreeUnit(
                fileObject,
                importItems,
                declarations,
                lineNumber,
                columnNumber
        );
    }

    public static ClassDeclaration classDeclaration(final Kind kind,
                                                    final NestingKind nestingKind,
                                                    final Modifiers modifiers,
                                                    final String simpleName,
                                                    final List<Tree> enclosedElements,
                                                    final List<TypeParameterTree> typeParameters,
                                                    final List<ExpressionTree> implementations,
                                                    final ExpressionTree extending,
                                                    final List<IdentifierTree> permits,
                                                    final int lineNumber,
                                                    final int columnNumber) {
        return new CClassDeclaration(
                kind,
                nestingKind,
                modifiers,
                simpleName,
                enclosedElements,
                typeParameters,
                implementations,
                extending,
                permits,
                lineNumber,
                columnNumber
        );
    }

    public static Function function(final String simpleName,
                                    final Kind kind,
                                    final Modifiers modifiers,
                                    final List<TypeParameterTree> typeParameters,
                                    final VariableDeclaratorTree receiveParameter,
                                    final List<VariableDeclaratorTree> parameters,
                                    final ExpressionTree returnType,
                                    final List<Tree> thrownTypes,
                                    final BlockStatementTree body,
                                    final ExpressionTree defaultValue,
                                    final int lineNumber,
                                    final int columnNumber) {
        return new CFunction(
                simpleName,
                kind,
                modifiers,
                typeParameters,
                receiveParameter,
                parameters,
                returnType,
                thrownTypes,
                body,
                defaultValue,
                lineNumber,
                columnNumber
        );
    }

    public static ErrorTree errorTree(final int lineNumber,
                                      final int columnNumber) {
        return new CErrorTree(lineNumber, columnNumber);
    }
}
