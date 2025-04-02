package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.resolve.AbstractResolver;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.MethodResolver;
import io.github.potjerodekool.nabu.compiler.resolve.scope.*;
import io.github.potjerodekool.nabu.compiler.tree.ImportItem;
import io.github.potjerodekool.nabu.compiler.tree.PackageDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatementTree;
import io.github.potjerodekool.nabu.compiler.type.*;

public class Phase2Resolver extends AbstractResolver {

    private final MethodResolver methodResolver;
    private final ClassElementLoader loader;
    private final PhaseUtils phaseUtils;

    public Phase2Resolver(final CompilerContextImpl compilerContext) {
        super(compilerContext);
        this.methodResolver = compilerContext.getMethodResolver();
        this.loader = compilerContext.getClassElementLoader();
        this.phaseUtils = new PhaseUtils(loader.getTypes());
    }

    @Override
    public Object visitPackageDeclaration(final PackageDeclaration packageDeclaration, final Scope scope) {
        scope.setPackageElement(packageDeclaration.getPackageElement());
        return null;
    }

    @Override
    public Object visitImportItem(final ImportItem importItem,
                                  final Scope scope) {
        final var isStatic = importItem.isStatic();
        final var isStarImport = importItem.isStarImport();
        final var classOrPackageName = importItem.getClassOrPackageName();

        if (isStatic) {
            if (isStarImport) {
                throw new TodoException();
            } else {
                throw new TodoException();
            }
        } else {
            if (isStarImport) {
                throw new TodoException();
            } else {
                //Single type import
                final var loader = compilerContext.getClassElementLoader();
                final var clazz = loader.loadClass(scope.findModuleElement(), classOrPackageName);

                if (clazz != null) {
                    importItem.setSymbol(clazz);
                }
            }
        }

        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {
        final var clazz = (ClassSymbol) classDeclaration.getClassSymbol();
        clazz.complete();

        final var classScope = new SymbolScope((DeclaredType) clazz.asType(), scope);
        if (classDeclaration.getExtending() != null) {
            final var superType = classDeclaration.getExtending().getType();
            //TODO check if this is a valid super type for the giving kind.
            //Supertype is already set in TypeEnter.
        }

        final var interfaces = classDeclaration.getImplementing().stream()
                .map(it -> {
                    it.accept(this, classScope);
                    return it.getType();
                }).toList();

        clazz.setInterfaces(interfaces);

        final var permits = classDeclaration.getPermits().stream()
                .map(permit -> {
                            permit.accept(this, classScope);
                            return (Symbol) permit.getType()
                                    .asTypeElement();
                        }
                ).toList();

        clazz.setPermitted(permits);

        classDeclaration.getEnclosedElements()
                .forEach(enclosingElement -> enclosingElement.accept(this, classScope));

        return null;
    }

    @Override
    public Object visitFunction(final Function function,
                                final Scope scope) {
        final var method = function.getMethodSymbol();
        final var functionScope = new FunctionScope(scope, method);

        if (!method.isStatic()) {
            final var type = method.getEnclosingElement().asType();

            final var thisVariable = new VariableSymbolBuilderImpl()
                    .kind(ElementKind.LOCAL_VARIABLE)
                    .name(Constants.THIS)
                    .type(type)
                    .build();

            functionScope.define(thisVariable);
        }

        return super.visitFunction(function, functionScope);
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final Scope scope) {
        super.visitVariableDeclaratorStatement(variableDeclaratorStatement, scope);

        if (variableDeclaratorStatement.getKind() != Kind.FIELD) {
            final var symbol = phaseUtils.createVariable(variableDeclaratorStatement);
            variableDeclaratorStatement.getName()
                    .setSymbol(symbol);

            if (variableDeclaratorStatement.getKind() == Kind.PARAMETER
                    || variableDeclaratorStatement.getKind() == Kind.LOCAL_VARIABLE) {
                scope.define(symbol);
            }
        }

        return null;
    }

    @Override
    public Object visitReturnStatement(final ReturnStatementTree returnStatement,
                                       final Scope scope) {
        final var expression = returnStatement.getExpression();

        if (expression instanceof LambdaExpressionTree lambdaExpression) {
            final var method = scope.getCurrentMethod();
            final var type = method.getReturnType();
            lambdaExpression.setType(type);
        }

        return super.visitReturnStatement(returnStatement, scope);
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                        final Scope scope) {
        final var target = methodInvocation.getTarget();

        if (target != null) {
            target.accept(this, scope);
        }

        methodInvocation.getArguments().forEach(arg -> arg.accept(this, scope));

        final var resolvedMethodType = methodResolver.resolveMethod(methodInvocation, scope.getCurrentElement());

        if (resolvedMethodType != null) {
            methodInvocation.setMethodType(resolvedMethodType);
            final var boxer = compilerContext.getArgumentBoxer();
            boxer.boxArguments(methodInvocation);
        }

        return null;
    }

    @Override
    public Object visitLiteralExpression(final LiteralExpressionTree literalExpression,
                                         final Scope scope) {
        final var loader = compilerContext.getClassElementLoader();
        final var types = loader.getTypes();

        final TypeMirror type = switch (literalExpression.getLiteralKind()) {
            case INTEGER -> types.getPrimitiveType(TypeKind.INT);
            case LONG -> types.getPrimitiveType(TypeKind.LONG);
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case STRING -> loader.loadClass(scope.findModuleElement(), Constants.STRING).asType();
            case NULL -> types.getNullType();
            case CLASS -> loader.loadClass(scope.findModuleElement(), Constants.CLAZZ).asType();
            case BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);
            case CHAR -> types.getPrimitiveType(TypeKind.CHAR);
        };

        literalExpression.setType(type);

        return null;
    }

    @Override
    public Object visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression, final Scope scope) {
        instanceOfExpression.getExpression().accept(this, scope);
        instanceOfExpression.getTypeExpression().accept(this, scope);
        return null;
    }
}