package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.resolve.AbstractResolver;
import io.github.potjerodekool.nabu.compiler.resolve.MethodResolver;
import io.github.potjerodekool.nabu.compiler.resolve.scope.*;
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
    private final PhaseUtils phaseUtils;

    public Phase2Resolver(final CompilerContextImpl compilerContext) {
        super(compilerContext);
        final var loader = compilerContext.getClassElementLoader();
        this.methodResolver = compilerContext.getMethodResolver();
        this.phaseUtils = new PhaseUtils(loader.getTypes());
    }

    @Override
    public Object visitPackageDeclaration(final PackageDeclaration packageDeclaration, final Scope scope) {
        scope.setPackageElement(packageDeclaration.getPackageElement());
        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {
        final var clazz = (ClassSymbol) classDeclaration.getClassSymbol();

        if (clazz == null) {
            //In case of new class expression
            return null;
        }

        clazz.complete();

        final var classScope = new SymbolScope((DeclaredType) clazz.asType(), scope);

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
                    .simpleName(Constants.THIS)
                    .type(type)
                    .build();

            functionScope.define(thisVariable);
        }

        return super.visitFunction(function, functionScope);
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final Scope scope) {
        super.visitVariableDeclaratorStatement(variableDeclaratorStatement, scope);

        if (!(variableDeclaratorStatement.getKind() == Kind.FIELD
                || variableDeclaratorStatement.getKind() == Kind.ENUM_CONSTANT)) {
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
        final var methodSelector = methodInvocation.getMethodSelector();

        if (methodSelector instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            fieldAccessExpressionTree.getSelected().accept(this, scope);
        }

        methodInvocation.getArguments().forEach(arg -> arg.accept(this, scope));

        final var resolvedMethodTypeOptional = methodResolver.resolveMethod(methodInvocation, scope.getCurrentElement(), scope);

        resolvedMethodTypeOptional.ifPresent(resolvedMethodType -> {
            methodSelector.setType(resolvedMethodType.getOwner().asType());
            methodInvocation.setMethodType(resolvedMethodType);
            final var boxer = compilerContext.getArgumentBoxer();
            boxer.boxArguments(methodInvocation);
        });

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

    @Override
    public Object visitBinaryExpression(final BinaryExpressionTree binaryExpression, final Scope scope) {
        return super.visitBinaryExpression(binaryExpression, scope);
    }
}