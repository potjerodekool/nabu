package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.impl.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.*;
import io.github.potjerodekool.nabu.compiler.tree.impl.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.*;
import io.github.potjerodekool.nabu.compiler.type.BoundKind;

import java.util.List;

public final class TreeMaker {

    private TreeMaker() {
    }

    public static IdentifierTree identifier(final String identifier,
                                            final int line,
                                            final int charPositionInLine) {
        return new CIdentifierTree(identifier, line, charPositionInLine);
    }

    public static PackageDeclaration packageDeclaration(final List<AnnotationTree> annotations,
                                                        final ExpressionTree identifier,
                                                        final int line,
                                                        final int charPositionInLine) {
        return new CPackageDeclarationTree(annotations, identifier, line, charPositionInLine);
    }

    public static ImportItem importItem(final FieldAccessExpressionTree qualified,
                                        final boolean isStatic,
                                        final int lineNumber,
                                        final int charPositionInLine) {
        return new CImportItemTree(
                qualified,
                isStatic,
                lineNumber,
                charPositionInLine);
    }

    public static VariableDeclaratorTree variableDeclarator(final Kind kind,
                                                            final CModifiers modifiers,
                                                            final ExpressionTree type,
                                                            final IdentifierTree name,
                                                            final ExpressionTree nameExpression,
                                                            final Tree value,
                                                            final int lineNumber,
                                                            final int charPositionInLine) {
        return new CVariableDeclaratorTree(
                kind,
                modifiers,
                type,
                name,
                nameExpression,
                value,
                lineNumber,
                charPositionInLine
        );
    }

    public static ReturnStatementTree returnStatement(final ExpressionTree expression,
                                                      final int lineNumber,
                                                      final int charPositionInLine) {
        return new CReturnStatementTree(expression, lineNumber, charPositionInLine);
    }


    public static LambdaExpressionTree lambdaExpressionTree(final List<VariableDeclaratorTree> parameters,
                                                            final StatementTree body,
                                                            final int lineNumber,
                                                            final int charPositionInLine) {
        return new CLambdaExpressionTree(
                parameters,
                body,
                lineNumber,
                charPositionInLine
        );
    }

    public static ExpressionStatementTree expressionStatement(final ExpressionTree expression,
                                                              final int line,
                                                              final int charPositionInLine) {
        return new CExpressionStatementTree(expression, line, charPositionInLine);
    }

    public static BinaryExpressionTree binaryExpressionTree(final ExpressionTree left,
                                                            final Tag tag,
                                                            final ExpressionTree right,
                                                            final int lineNumber,
                                                            final int charPositionInLine) {
        return new CBinaryExpressionTree(
                left,
                tag,
                right,
                lineNumber,
                charPositionInLine
        );
    }

    public static InstanceOfExpression instanceOfExpression(final ExpressionTree expression,
                                                            final ExpressionTree typeExpression,
                                                            final int lineNumber,
                                                            final int charPositionInLine) {
        return new CInstanceOfExpression(
                expression,
                typeExpression,
                lineNumber,
                charPositionInLine
        );
    }

    public static UnaryExpressionTree unaryExpressionTree(final Tag tag,
                                                          final ExpressionTree expression,
                                                          final int lineNumber,
                                                          final int charPositionInLine) {
        return new CUnaryExpressionTree(
                tag,
                expression,
                lineNumber,
                charPositionInLine
        );
    }

    public static CastExpressionTree castExpressionTree(final ExpressionTree targetType,
                                                        final ExpressionTree expression,
                                                        final int lineNumber,
                                                        final int charPositionInLine) {
        return new CCastExpressionTree(targetType, expression, lineNumber, charPositionInLine);
    }

    public static FieldAccessExpressionTree fieldAccessExpressionTree(final ExpressionTree target,
                                                                      final ExpressionTree field,
                                                                      final int lineNumber,
                                                                      final int charPositionInLine) {
        return new CFieldAccessExpressionTree(target, field, lineNumber, charPositionInLine);
    }

    public static MethodInvocationTree methodInvocationTree(final ExpressionTree target,
                                                            final ExpressionTree name,
                                                            final List<IdentifierTree> typeArguments,
                                                            final List<ExpressionTree> arguments,
                                                            final int lineNumber,
                                                            final int charPositionInLine) {
        return new CMethodInvocationTree(
                target,
                name,
                typeArguments,
                arguments,
                lineNumber,
                charPositionInLine
        );
    }

    public static PrimitiveTypeTree primitiveTypeTree(final PrimitiveTypeTree.Kind kind,
                                                      final int lineNumber,
                                                      final int charPositionInLine) {
        return new CPrimitiveTypeTree(kind, lineNumber, charPositionInLine);
    }

    public static TypeApplyTree typeApplyTree(final ExpressionTree clazz,
                                              final List<? extends ExpressionTree> typeParameters,
                                              final int lineNumber,
                                              final int charPositionInLine) {
        return new CTypeApplyTree(clazz, typeParameters, lineNumber, charPositionInLine);
    }

    public static VariableTypeTree variableTypeTree(final int lineNumber,
                                                    final int charPositionInLine) {
        return new CVariableTypeTree(
                lineNumber,
                charPositionInLine
        );
    }

    public static LiteralExpressionTree literalExpressionTree(final Object literal,
                                                              final int lineNumber,
                                                              final int charPositionInLine) {
        return new CLiteralExpressionTree(literal, lineNumber, charPositionInLine);
    }

    public static WildcardExpressionTree wildcardExpressionTree(final BoundKind boundKind,
                                                                final ExpressionTree bound,
                                                                final int lineNumber,
                                                                final int charPositionInLine) {
        return new CWildcardExpressionTree(
                boundKind,
                bound,
                lineNumber,
                charPositionInLine
        );
    }

    public static IfStatementTree ifStatementTree(final ExpressionTree expression,
                                                  final StatementTree thenStatement,
                                                  final StatementTree elseStatement,
                                                  final int lineNumber,
                                                  final int charPositionInLine) {
        return new CIfStatementTree(
                expression,
                thenStatement,
                elseStatement,
                lineNumber,
                charPositionInLine
        );
    }

    public static BlockStatementTree blockStatement(final List<StatementTree> statements,
                                                    final int lineNumber,
                                                    final int charPositionInLine) {
        return new CBlockStatementTree(statements, lineNumber, charPositionInLine);
    }

    public static EmptyStatementTree emptyStatementTree(final int lineNumber,
                                                        final int charPositionInLine) {
        return new CEmptyStatementTree(lineNumber, charPositionInLine);
    }

    public static ForStatementTree forStatement(final List<StatementTree> forInit,
                                                final ExpressionTree expression,
                                                final List<StatementTree> forUpdate,
                                                final StatementTree statement,
                                                final int lineNumber,
                                                final int charPositionInLine) {
        return new CForStatementTree(
                forInit,
                expression,
                forUpdate,
                statement,
                lineNumber,
                charPositionInLine
        );
    }

    public static AnnotatedTypeTree annotatedTypeTree(final List<AnnotationTree> annotations,
                                                      final ExpressionTree clazz,
                                                      final List<ExpressionTree> arguments,
                                                      final int lineNumber,
                                                      final int charPositionInLine) {
        return new CAnnotatedTypeTree(
                annotations,
                clazz,
                arguments,
                lineNumber,
                charPositionInLine
        );
    }

    public static EnhancedForStatementTree enhancedForStatement(final VariableDeclaratorTree localVariable,
                                                                final ExpressionTree expression,
                                                                final StatementTree statement,
                                                                final int lineNumber,
                                                                final int charPositionInLine) {
        return new CEnhancedForStatementTree(
                localVariable,
                expression,
                statement,
                lineNumber,
                charPositionInLine
        );
    }

    public static AnnotationTree annotationTree(final IdentifierTree name,
                                                final List<ExpressionTree> arguments,
                                                final int lineNumber,
                                                final int charPositionInLine) {
        return new CAnnotationTree(
                name,
                arguments,
                lineNumber,
                charPositionInLine
        );
    }

    public static NewClassExpression newClassExpression(final ExpressionTree name,
                                                        final List<ExpressionTree> typeArguments,
                                                        final List<ExpressionTree> arguments,
                                                        final ClassDeclaration classDeclaration,
                                                        final int lineNumber,
                                                        final int charPositionInLine) {
        return new CNewClassExpression(
                name,
                typeArguments,
                arguments,
                classDeclaration,
                lineNumber,
                charPositionInLine
        );
    }

    public static WhileStatementTree whileStatement(final ExpressionTree condition,
                                                    final StatementTree body,
                                                    final int lineNumber,
                                                    final int charPositionInLine) {
        return new CWhileStatementTree(
                condition,
                body,
                lineNumber,
                charPositionInLine
        );
    }

    public static DoWhileStatementTree doWhileStatement(final StatementTree body,
                                                        final ExpressionTree condition,
                                                        final int lineNumber,
                                                        final int charPositionInLine) {
        return new CDoWhileStatementTree(
                body,
                condition,
                lineNumber,
                charPositionInLine
        );
    }

    public static AssignmentExpressionTree assignmentExpression(final ExpressionTree left,
                                                                final ExpressionTree right,
                                                                final int lineNumber,
                                                                final int charPositionInLine) {
        return new CAssignmentExpressionTree(
                left,
                right,
                lineNumber,
                charPositionInLine
        );
    }

    public static TypeParameterTree typeParameterTree(final List<AnnotationTree> annotations,
                                                      final IdentifierTree identifier,
                                                      final List<ExpressionTree> typeBound,
                                                      final int lineNumber,
                                                      final int charPositionInLine) {
        return new CTypeParameterTree(
                annotations,
                identifier,
                typeBound,
                lineNumber,
                charPositionInLine
        );
    }

    public static TypeVariableTree typeVariableTree(final List<AnnotationTree> annotations,
                                                    final IdentifierTree identifier,
                                                    final int lineNumber,
                                                    final int charPositionInLine) {
        return new CTypeVariableTree(
                annotations,
                identifier,
                lineNumber,
                charPositionInLine
        );
    }

    public static ArrayTypeTree arrayTypeTree(final Tree componentType,
                                              final int lineNumber,
                                              final int charPositionInLine) {
        return new CArrayTypeTree(
                componentType,
                lineNumber,
                charPositionInLine
        );
    }

    public static NewArrayExpression newArrayExpression(final List<ExpressionTree> elements,
                                                        final int lineNumber,
                                                        final int charPositionInLine) {
        return new CNewArrayExpression(
                elements,
                lineNumber,
                charPositionInLine
        );
    }

    public static CompilationUnit compilationUnit(final FileObject fileObject,
                                                  final List<ImportItem> importItems,
                                                  final List<Tree> declarations,
                                                  final int lineNumber,
                                                  final int charPositionInLine) {
        return new CCompilationTreeUnit(
                fileObject,
                importItems,
                declarations,
                lineNumber,
                charPositionInLine
        );
    }

    public static ClassDeclaration classDeclaration(final Kind kind,
                                                    final CModifiers modifiers,
                                                    final String simpleName,
                                                    final List<Tree> enclosedElements,
                                                    final List<TypeParameterTree> typeParameters,
                                                    final List<ExpressionTree> implementations,
                                                    final ExpressionTree extending,
                                                    final List<IdentifierTree> permits,
                                                    final int lineNumber,
                                                    final int charPositionInLine) {
        return new CClassDeclaration(
                kind,
                modifiers,
                simpleName,
                enclosedElements,
                typeParameters,
                implementations,
                extending,
                permits,
                lineNumber,
                charPositionInLine
        );
    }

    public static Function function(final String simpleName,
                                    final Kind kind,
                                    final CModifiers modifiers,
                                    final List<TypeParameterTree> typeParameters,
                                    final VariableDeclaratorTree receiveParameter,
                                    final List<VariableDeclaratorTree> parameters,
                                    final ExpressionTree returnType,
                                    final List<Tree> thrownTypes,
                                    final BlockStatementTree body,
                                    final ExpressionTree defaultValue,
                                    final int lineNumber,
                                    final int charPositionInLine) {
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
                charPositionInLine
        );
    }

    public static ErrorTree errorTree(final int lineNumber,
                                      final int charPositionInLine) {
        return new CErrorTree(lineNumber, charPositionInLine);
    }
}
