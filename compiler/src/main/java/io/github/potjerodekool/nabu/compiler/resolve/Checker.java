package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.diagnostic.DefaultDiagnostic;
import io.github.potjerodekool.nabu.compiler.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.compiler.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.access.StandardAccessChecker;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ClassScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.expression.CIdent;
import io.github.potjerodekool.nabu.compiler.tree.expression.CTypeApply;
import io.github.potjerodekool.nabu.compiler.tree.expression.MethodInvocation;
import io.github.potjerodekool.nabu.compiler.type.ErrorType;

public class Checker extends AbstractTreeVisitor<Object, Scope> {

    private final DiagnosticListener listener;

    public Checker(final DiagnosticListener listener) {
        this.listener = listener;
    }

    private CompilationUnit getCompilationUnit(final Scope scope) {
        return scope.getCompilationUnit();
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Scope scope) {
        return super.visitCompilationUnit(compilationUnit, new GlobalScope(compilationUnit));
    }

    @Override
    public Object visitClass(final CClassDeclaration classDeclaration,
                             final Scope scope) {
        final var classScope = new ClassScope(
                classDeclaration.classSymbol,
                scope
        );
        return super.visitClass(classDeclaration, classScope);
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocation methodInvocation,
                                        final Scope scope) {
        methodInvocation.getTarget().accept(this, scope);

        if (methodInvocation.getMethodType() == null) {
            final var methodName = ((CIdent) methodInvocation.getName()).getName();

            final var compilationUnit = getCompilationUnit(scope);

            listener.report(new DefaultDiagnostic(
                    Diagnostic.Kind.ERROR,
                    "Failed to resolve method" + methodName,
                    compilationUnit.getFileObject()));
        }

        methodInvocation.getArguments().forEach(arg -> arg.accept(this, scope));

        return null;
    }

    @Override
    public Object visitIdentifier(final CIdent ident,
                                  final Scope scope){
        final var symbol = ident.getSymbol();
        final var type = ident.getType();

        if (symbol == null && type == null) {
            final var linInfo = formatLineInfo(ident);
            listener.report(new DefaultDiagnostic(
                    Diagnostic.Kind.ERROR,
                    String.format("Failed to resolve symbol %s %s", ident.getName(), linInfo),
                    getCompilationUnit(scope).getFileObject()
            ));
        } else {
            final var currentClass = scope.getCurrentClass();

            if (StandardAccessChecker.INSTANCE.isAccessible(symbol, currentClass)) {
                ident.setSymbol(symbol);
            } else {
                reportNotAccessible(symbol, currentClass, scope);
                ident.setSymbol(symbol);
            }
        }

        return null;
    }

    private String formatLineInfo(final Tree tree) {
        return "at " + tree.getLineNumber() + ":" + tree.getColumnNumber();
    }

    private void reportNotAccessible(final Element element,
                                     final ClassSymbol currentClass,
                                     final Scope scope) {
        final String message;
        final var className = currentClass.getQualifiedName();

        if (element instanceof VariableElement variableElement
                && variableElement.getKind() == ElementKind.FIELD) {
            final var ownerClass = (ClassSymbol) variableElement.getEnclosingElement();

            message = String.format("Field %s of %s is not accessible from %s",
                    variableElement.getSimpleName(),
                    ownerClass.getQualifiedName(),
                    className);
        } else {
            throw new TodoException();
        }

        listener.report(new DefaultDiagnostic(
                Diagnostic.Kind.WARN,
                message,
                getCompilationUnit(scope).getFileObject()));
    }

    @Override
    public Object visitTypeIdentifier(final CTypeApply typeIdentifier,
                                      final Scope scope) {
        var type = typeIdentifier.getType();

        if (type instanceof ErrorType errorType) {
            listener.report(new DefaultDiagnostic(
                    Diagnostic.Kind.ERROR,
                    "Failed to resolve " + errorType.getClassName(),
                    getCompilationUnit(scope).getFileObject()));
        }

        return super.visitTypeIdentifier(typeIdentifier, scope);
    }
}
