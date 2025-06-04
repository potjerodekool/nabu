package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.diagnostic.DefaultDiagnostic;
import io.github.potjerodekool.nabu.compiler.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.compiler.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.access.StandardAccessChecker;
import io.github.potjerodekool.nabu.compiler.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.SymbolScope;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.ImportItem;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.SwitchStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.ThrowStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.ErrorType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class Checker extends AbstractTreeVisitor<Object, Scope> {

    private final DiagnosticListener listener;

    public Checker(final DiagnosticListener listener) {
        this.listener = listener;
    }

    private CompilationUnit getCompilationUnit(final Scope scope) {
        return scope.getCompilationUnit();
    }

    @Override
    public Object visitUnknown(final Tree tree, final Scope scope) {
        return null;
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Scope scope) {
        return super.visitCompilationUnit(compilationUnit, new GlobalScope(compilationUnit, null));
    }

    @Override
    public Object visitImportItem(final ImportItem importItem,
                                  final Scope scope) {
        if (importItem.getSymbol() == null) {
            final var className = importItem.getClassOrPackageName();

            reportUnresolvedSymbol(
                    className,
                    importItem,
                    scope
            );
        }

        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {
        final var clazz = classDeclaration.getClassSymbol();

        if (clazz == null) {
            return null;
        }

        final var classScope = new SymbolScope(
                (DeclaredType) clazz.asType(),
                scope
        );
        return super.visitClass(classDeclaration, classScope);
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                        final Scope scope) {
        if (methodInvocation.getMethodType() == null) {
            final var methodSelector = methodInvocation.getMethodSelector();

            final var methodName = methodSelector instanceof FieldAccessExpressionTree fieldAccessExpressionTree
                    ? ((IdentifierTree) fieldAccessExpressionTree.getSelected()).getName()
                    : ((IdentifierTree) methodSelector).getName();

            final var compilationUnit = getCompilationUnit(scope);

            listener.report(new DefaultDiagnostic(
                    Diagnostic.Kind.ERROR,
                    "Failed to resolve method " + methodName,
                    compilationUnit.getFileObject()));
        }

        methodInvocation.getArguments().forEach(arg -> arg.accept(this, scope));

        return null;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final Scope scope) {
        final var symbol = identifier.getSymbol();
        final var type = identifier.getType();

        if (isNullOrError(symbol) && isNullOrErrorType(type)) {
            reportUnresolvedSymbol(
                    identifier.getName(),
                    identifier,
                    scope
            );
        } else {
            final var currentClass = scope.getCurrentClass();

            if (StandardAccessChecker.INSTANCE.isAccessible(symbol, currentClass)) {
                identifier.setSymbol(symbol);
            } else {
                reportNotAccessible(symbol, currentClass, scope);
                identifier.setSymbol(symbol);
            }
        }

        return null;
    }

    private void reportUnresolvedSymbol(final String name,
                                        final Tree tree,
                                        final Scope scope) {
        final var lineInfo = formatLineInfo(tree);
        listener.report(new DefaultDiagnostic(
                Diagnostic.Kind.ERROR,
                String.format("Failed to resolve symbol %s %s", name, lineInfo),
                getCompilationUnit(scope).getFileObject()
        ));
    }

    private boolean isNullOrError(final Element element) {
        return element == null
                || !element.exists();
    }

    private String formatLineInfo(final Tree tree) {
        return "at " + tree.getLineNumber() + ":" + tree.getColumnNumber();
    }

    private void reportNotAccessible(final Element element,
                                     final TypeElement currentClass,
                                     final Scope scope) {
        final String message;
        final var className = currentClass.getQualifiedName();

        if (element instanceof VariableElement variableElement) {
            final var ownerClass = (TypeElement) variableElement.getEnclosingElement();

            final var elementType = variableElement.getKind() == ElementKind.ENUM_CONSTANT
                    ? "Enum constant"
                    : "Field";

            message = String.format("%s %s of %s is not accessible from %s",
                    elementType,
                    variableElement.getSimpleName(),
                    ownerClass.getQualifiedName(),
                    className);
        } else {
            message = String.format("%s is not accessible from %s",
                    element.getSimpleName(),
                    className);
        }

        listener.report(new DefaultDiagnostic(
                Diagnostic.Kind.ERROR,
                message,
                getCompilationUnit(scope).getFileObject()));
    }

    @Override
    public Object visitTypeIdentifier(final TypeApplyTree typeIdentifier,
                                      final Scope scope) {
        if (isNullOrErrorType(typeIdentifier.getType())) {
            reportFailedToResolveType(
                    typeIdentifier.getClazz(),
                    scope
            );
        }

        return super.visitTypeIdentifier(typeIdentifier, scope);
    }

    private void reportFailedToResolveType(final ExpressionTree expressionTree,
                                           final Scope scope) {
        final var className = new StringBuilder();
        resolveClassName(expressionTree, className);

        listener.report(new DefaultDiagnostic(
                Diagnostic.Kind.ERROR,
                "Failed to resolve " + className,
                getCompilationUnit(scope).getFileObject()));
    }

    private boolean isNullOrErrorType(final TypeMirror typeMirror) {
        return typeMirror == null || typeMirror instanceof ErrorType;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final Scope scope) {
        final var type = variableDeclaratorStatement.getType().getType();

        if (isNullOrErrorType(type)) {
            reportFailedToResolveType(variableDeclaratorStatement.getType(), scope);
        }

        if (variableDeclaratorStatement.getValue() != null) {
            variableDeclaratorStatement.getValue().accept(this, scope);
        }

        return null;
    }

    private void resolveClassName(final ExpressionTree expressionTree,
                                  final StringBuilder builder) {
        if (expressionTree instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            resolveClassName(fieldAccessExpressionTree.getSelected(), builder);
            builder.append(".");
            resolveClassName(fieldAccessExpressionTree.getField(), builder);
        } else if (expressionTree instanceof IdentifierTree identifierTree) {
            builder.append(identifierTree.getName());
        }
    }

    @Override
    public Object visitSwitchStatement(final SwitchStatement switchStatement, final Scope scope) {
        return super.visitSwitchStatement(switchStatement, scope);
    }

    @Override
    public Object visitThrowStatement(final ThrowStatement throwStatement, final Scope scope) {
        throwStatement.getExpression().accept(this, scope);
        return null;
    }

    @Override
    public Object visitNewClass(final NewClassExpression newClassExpression, final Scope scope) {
        newClassExpression.getName().accept(this, scope);
        return null;
    }
}
