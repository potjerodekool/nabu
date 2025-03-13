package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.diagnostic.DefaultDiagnostic;
import io.github.potjerodekool.nabu.compiler.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.compiler.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.access.StandardAccessChecker;
import io.github.potjerodekool.nabu.compiler.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.SymbolScope;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.TypeApplyTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocationTree;
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
    public Object visitUnknown(final Tree tree, final Scope Param) {
        return null;
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Scope scope) {
        return super.visitCompilationUnit(compilationUnit, new GlobalScope(compilationUnit, null));
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {
        final var classScope = new SymbolScope(
                (DeclaredType) classDeclaration.getClassSymbol().asType(),
                scope
        );
        return super.visitClass(classDeclaration, classScope);
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                        final Scope scope) {
        if (methodInvocation.getTarget() != null) {
            methodInvocation.getTarget().accept(this, scope);
        }

        if (methodInvocation.getMethodType() == null) {
            final var methodName = ((IdentifierTree) methodInvocation.getName()).getName();

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
                                  final Scope scope){
        final var symbol = identifier.getSymbol();
        final var type = identifier.getType();

        if (symbol == null && isNullOrErrorType(type)) {
            final var linInfo = formatLineInfo(identifier);
            listener.report(new DefaultDiagnostic(
                    Diagnostic.Kind.ERROR,
                    String.format("Failed to resolve symbol %s %s", identifier.getName(), linInfo),
                    getCompilationUnit(scope).getFileObject()
            ));
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

    private String formatLineInfo(final Tree tree) {
        return "at " + tree.getLineNumber() + ":" + tree.getColumnNumber();
    }

    private void reportNotAccessible(final Element element,
                                     final TypeElement currentClass,
                                     final Scope scope) {
        final String message;
        final var className = currentClass.getQualifiedName();

        if (element instanceof VariableElement variableElement
                && variableElement.getKind() == ElementKind.FIELD) {
            final var ownerClass = (TypeElement) variableElement.getEnclosingElement();

            message = String.format("Field %s of %s is not accessible from %s",
                    variableElement.getSimpleName(),
                    ownerClass.getQualifiedName(),
                    className);
        } else {
            throw new TodoException();
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
            listener.report(new DefaultDiagnostic(
                    Diagnostic.Kind.ERROR,
                    "Failed to resolve " + typeIdentifier.getName(),
                    getCompilationUnit(scope).getFileObject()));
        }

        return super.visitTypeIdentifier(typeIdentifier, scope);
    }

    private boolean isNullOrErrorType(final TypeMirror typeMirror) {
        return typeMirror == null || typeMirror instanceof ErrorType;
    }
}
