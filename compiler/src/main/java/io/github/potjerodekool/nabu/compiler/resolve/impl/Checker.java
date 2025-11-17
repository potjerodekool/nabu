package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.scope.SymbolScope;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.compiler.resolve.access.StandardAccessChecker;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tools.diagnostic.DefaultDiagnostic;
import io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.tools.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.impl.CVariableTypeTree;
import io.github.potjerodekool.nabu.tree.statement.SwitchStatement;
import io.github.potjerodekool.nabu.tree.statement.ThrowStatement;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.ErrorType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.TypePrinter;

import java.io.File;
import java.util.stream.Collectors;

public class Checker extends AbstractTreeVisitor<Object, Scope> {

    private final CompilerContext compilerContext;
    private final DiagnosticListener listener;

    public Checker(final CompilerContext compilerContext,
                   final DiagnosticListener listener) {
        this.compilerContext = compilerContext;
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
        if (importItem.getSymbol() == null
            || importItem.getSymbol().isError()) {
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
        final var clazz = (ClassSymbol) classDeclaration.getClassSymbol();

        if (clazz == null) {
            return null;
        }

        topLevelClassCheck(clazz, scope);

        final var classScope = new SymbolScope(
                (DeclaredType) clazz.asType(),
                scope
        );
        return super.visitClass(classDeclaration, classScope);
    }

    private void topLevelClassCheck(final ClassSymbol clazz,
                                    final Scope scope) {
        if (clazz.getNestingKind() == NestingKind.TOP_LEVEL
                && clazz.isPublic()
            && clazz.getSourceFile() != null) {
            final var simpleName = getSimpleName(clazz.getSourceFile());

            if (!simpleName.equals(clazz.getSimpleName())) {
                reportError(String.format("Class '%s' is public, should be declared in a file named '%s.nabu'", clazz.getSimpleName(), simpleName), scope);
            }
        }
    }

    private String getSimpleName(final FileObject sourceFile) {
        final var fileName = sourceFile.getFileName();
        final var start = fileName.lastIndexOf(File.separatorChar) + 1;
        final var end = fileName.lastIndexOf('.');
        return fileName.substring(start, end);
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                        final Scope scope) {
        if (methodInvocation.getMethodType() == null) {
            final var methodSelector = methodInvocation.getMethodSelector();

            final var methodName = methodSelector instanceof FieldAccessExpressionTree fieldAccessExpressionTree
                    ? fieldAccessExpressionTree.getField().getName()
                    : ((IdentifierTree) methodSelector).getName();

            final var compilationUnit = getCompilationUnit(scope);
            final var lineInfo = formatLineInfo(methodInvocation);

            reportError(
                    "Failed to resolve method " + methodName + createMethodSignature(methodInvocation) +  " " + lineInfo,
                    compilationUnit
            );
        }

        methodInvocation.getArguments().forEach(arg -> arg.accept(this, scope));

        return null;
    }

    private String createMethodSignature(final MethodInvocationTree methodInvocationTree) {
        return methodInvocationTree.getArguments().stream()
                .map(argument -> compilerContext.getTreeUtils().typeOf(argument))
                .map(TypePrinter::print)
                .collect(Collectors.joining(",", "(", ")"));
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
        reportError(String.format("Failed to resolve symbol %s %s", name, lineInfo), scope);
    }

    private void reportError(final String message,
                             final Scope scope) {
        reportError(message, getCompilationUnit(scope));
    }

    private void reportError(final String message,
                             final CompilationUnit compilationUnit) {
        listener.report(new DefaultDiagnostic(
                Diagnostic.Kind.ERROR,
                message,
                compilationUnit.getFileObject()
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

        reportError(message, scope);
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
        final var lineInfo = formatLineInfo(expressionTree);
        reportError("Failed to resolve class " + className + " " + lineInfo, scope);
    }

    private boolean isNullOrErrorType(final TypeMirror typeMirror) {
        return typeMirror == null || typeMirror instanceof ErrorType;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final Scope scope) {
        final var type = variableDeclaratorStatement.getVariableType().getType();

        if (isNullOrErrorType(type)) {
            reportFailedToResolveType(variableDeclaratorStatement.getVariableType(), scope);
        }

        if (variableDeclaratorStatement.getValue() != null) {
            variableDeclaratorStatement.getValue().accept(this, scope);
        }

        return null;
    }

    private void resolveClassName(final ExpressionTree expressionTree,
                                  final StringBuilder builder) {
        switch (expressionTree) {
            case FieldAccessExpressionTree fieldAccessExpressionTree -> {
                resolveClassName(fieldAccessExpressionTree.getSelected(), builder);
                builder.append(".");
                resolveClassName(fieldAccessExpressionTree.getField(), builder);
            }
            case IdentifierTree identifierTree -> builder.append(identifierTree.getName());
            case CVariableTypeTree ignored -> builder.append("var");
            case TypeApplyTree typeApplyTree -> resolveClassName(typeApplyTree.getClazz(), builder);
            case null, default -> {
            }
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

    @Override
    public Object visitAnnotation(final AnnotationTree annotationTree, final Scope scope) {
        final var annotationType = annotationTree.getName().getType();

        if (isNullOrErrorType(annotationType)) {
            reportError(
                    String.format("Failed to resolve %s at %s:%s",
                            annotationTree.getName().getName(),
                            annotationTree.getName().getLineNumber(),
                            annotationTree.getName().getColumnNumber()
                    ), scope);
        }

        annotationTree.getArguments().forEach(argument -> argument.accept(this, scope));

        return null;
    }
}
