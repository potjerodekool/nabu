package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.*;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ClassScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.FunctionScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;
import io.github.potjerodekool.nabu.compiler.type.*;

import static io.github.potjerodekool.nabu.compiler.resolve.TreeUtils.getSymbol;
import static io.github.potjerodekool.nabu.compiler.resolve.TreeUtils.resolveType;

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
        final var clazz = classDeclaration.classSymbol;
        final var classScope = new ClassScope(clazz.asType(), scope);

        classDeclaration.getEnclosedElements()
                .forEach(enclosingElement -> enclosingElement.accept(this, classScope));

        return null;
    }

    @Override
    public Object visitFunction(final Function function,
                                final Scope scope) {
        final var method = function.methodSymbol;
        final var functionScope = new FunctionScope(scope, method);

        if (!method.isStatic()) {
            final var type = method.getEnclosingElement().asType();

            final var thisVariable = new VariableBuilder()
                    .kind(ElementKind.VARIABLE)
                    .name(Constants.THIS)
                    .type(type)
                            .build();

            functionScope.define(thisVariable);
        }

        return super.visitFunction(function, functionScope);
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

        final var resolvedMethodType = methodResolver.resolveMethod(methodInvocation);

        if (resolvedMethodType != null) {
            methodInvocation.setMethodType(resolvedMethodType);
            final var boxer = compilerContext.getArgumentBoxer();
            boxer.boxArguments(methodInvocation);
        }

        return null;
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressioTree fieldAccessExpression,
                                             final Scope scope) {
        fieldAccessExpression.getTarget().accept(this, scope);

        final var symbol = getSymbol(fieldAccessExpression.getTarget());

        final var classType = switch (symbol) {
            case null -> (DeclaredType) resolveType(fieldAccessExpression.getTarget());
            case VariableElement variableElement -> {
                final var varType = variableElement.asType();

                if (varType instanceof DeclaredType ct) {
                    yield ct;
                } else {
                    final var ct = (DeclaredType) ((VariableType)varType).getInterferedType();

                    if (ct == null) {
                        throw new TodoException();
                    }
                    yield ct;
                }
            }
            case TypeElement classSymbol -> (DeclaredType) classSymbol.asType();
            default -> throw new TodoException();
        };

        if (classType != null) {
            final var targetScope = new ClassScope(
                    classType,
                    scope.getGlobalScope()
            );
            fieldAccessExpression.getField().accept(this, targetScope);
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
            case STRING -> loader.resolveClass(Constants.STRING).asType();
            case NULL -> types.getNullType();
            case CLASS -> loader.resolveClass(Constants.CLAZZ).asType();
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