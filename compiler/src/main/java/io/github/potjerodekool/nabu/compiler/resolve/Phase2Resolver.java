package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.scope.*;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclarator;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;
import io.github.potjerodekool.nabu.compiler.type.*;

public class Phase2Resolver extends AbstractResolver {

    private final MethodResolver methodResolver;

    public Phase2Resolver(final CompilerContext compilerContext) {
        super(compilerContext);
        this.methodResolver = compilerContext.getMethodResolver();
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Scope scope) {
        final var globalScope = new GlobalScope(
                compilationUnit,
                compilerContext
        );
        return super.visitCompilationUnit(compilationUnit, globalScope);
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {
        final var clazz = classDeclaration.getClassSymbol();
        final var classScope = new SymbolScope((DeclaredType) clazz.asType(), scope);

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

            final var thisVariable = new VariableBuilder()
                    .kind(ElementKind.LOCAL_VARIABLE)
                    .name(Constants.THIS)
                    .type(type)
                            .build();

            functionScope.define(thisVariable);
        }

        return super.visitFunction(function, functionScope);
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclarator variableDeclaratorStatement, final Scope scope) {
        super.visitVariableDeclaratorStatement(variableDeclaratorStatement, scope);

        if (variableDeclaratorStatement.getKind() != Kind.FIELD) {
            final var symbol = createVariable(variableDeclaratorStatement);
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
    public Object visitReturnStatement(final ReturnStatement returnStatement,
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
            case INTEGER ->  types.getPrimitiveType(TypeKind.INT);
            case LONG -> types.getPrimitiveType(TypeKind.LONG);
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case STRING -> loader.loadClass(Constants.STRING).asType();
            case NULL -> types.getNullType();
            case CLASS -> loader.loadClass(Constants.CLAZZ).asType();
            case BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);
            case CHAR -> types.getPrimitiveType(TypeKind.CHAR);
        };

        literalExpression.setType(type);

        return null;
    }

}