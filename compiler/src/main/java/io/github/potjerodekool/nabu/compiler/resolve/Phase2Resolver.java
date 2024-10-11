package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.*;
import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ClassScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.FunctionScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.transform.JpaTransformer;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.lang.jpa.JpaPredicate;

import java.util.HashMap;
import java.util.Map;

public class Phase2Resolver extends AbstractResolver {

    private final MethodResolver methodResolver;
    private final Map<String, Class<? extends AbstractTreeVisitor<?, Scope>>> transformers = new HashMap<>();

    public Phase2Resolver(final CompilerContext compilerContext) {
        super(compilerContext);
        this.methodResolver = compilerContext.getMethodResolver();

        this.transformers.put(
                JpaPredicate.class.getName(),
                JpaTransformer.class
        );
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Scope scope) {
        final var globalScope = new GlobalScope(compilationUnit);
        return super.visitCompilationUnit(compilationUnit, globalScope);
    }

    @Override
    public Object visitClass(final CClassDeclaration classDeclaration,
                             final Scope scope) {
        final var clazz = classDeclaration.classSymbol;
        final var classScope = new ClassScope(clazz, scope);

        classDeclaration.getEnclosedElements()
                .forEach(enclosingElement -> enclosingElement.accept(this, classScope));

        return null;
    }

    @Override
    public Object visitFunction(final CFunction function,
                                final Scope scope) {
        final var method = function.methodSymbol;
        final var functionScope = new FunctionScope(scope, method);
        return super.visitFunction(function, functionScope);
    }

    @Override
    public Object visitReturnStatement(final ReturnStatement returnStatement,
                                       final Scope scope) {
        final var expression = returnStatement.getExpression();

        if (expression instanceof CLambdaExpression lambdaExpression) {
            final var method = scope.getCurrentMethod();
            final var type = method.getMethodType().getReturnType();
            lambdaExpression.setType(type);
        }

        return super.visitReturnStatement(returnStatement, scope);
    }

    @Override
    public Object visitVariable(final CVariable variable,
                                final Scope scope) {
        scope.define(variable.getVarSymbol());
        variable.getType().accept(this, scope);
        return null;
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocation methodInvocation,
                                        final Scope scope) {
        var clazz = (ClassSymbol) scope.resolve("this");

        final var target = methodInvocation.getTarget();

        if (target != null) {
            target.accept(this, scope);
            final var symbol = target.getSymbol();

            if (symbol instanceof ClassSymbol classSymbol) {
                clazz = classSymbol;
            } else if (symbol instanceof VariableElement variableElement) {
                final var variableType = TypeUtils.INSTANCE.asClassType(variableElement.getVariableType());
                clazz = (ClassSymbol) variableType.asElement();
            }
        }

        methodInvocation.getArguments().forEach(arg -> arg.accept(this, scope));

        final var methodName = (CIdent) methodInvocation.getName();
        final var argumentTypes = methodInvocation.getArguments().stream()
                        .map(CExpression::getType)
                        .toList();

        final var resolvedMethodType = methodResolver.resolveMethod(
                (MutableClassType) clazz.asType(),
                methodName.getName(),
                argumentTypes
        );

        if (resolvedMethodType != null) {
            methodInvocation.setMethodType(resolvedMethodType);
            final var boxer = compilerContext.getArgumentBoxer();
            boxer.boxArguments(methodInvocation);
        }

        return null;
    }

    @Override
    public Object visitFieldAccessExpression(final CFieldAccessExpression fieldAccessExpression,
                                             final Scope scope) {
        fieldAccessExpression.getTarget().accept(this, scope);

        final var symbol = getSymbol(fieldAccessExpression.getTarget());
        final ClassSymbol classSymbol;

        if (symbol instanceof VariableElement variableElement) {
            final var varType = variableElement.getVariableType();
            final ClassType classType;

            if (varType instanceof ClassType ct) {
                classType = ct;
            } else {
                classType = (ClassType) ((VariableType)varType).getInterferedType();
            }

            if (classType == null) {
                throw new TodoException();
            }

            classSymbol = (ClassSymbol) classType.asElement();
        } else if (symbol instanceof ClassSymbol cs) {
            classSymbol = cs;
        } else {
            throw new TodoException();
        }

        final var targetScope = new ClassScope(
                classSymbol,
                scope.getGlobalScope()
        );
        fieldAccessExpression.getField().accept(this, targetScope);
        return null;
    }

    private Element getSymbol(final CExpression expression) {
        if (expression instanceof CIdent) {
            return expression.getSymbol();
        } else if (expression instanceof CFieldAccessExpression fieldAccessExpression) {
            return getSymbol(fieldAccessExpression.getField());
        } else {
            throw new TodoException();
        }
    }

    @Override
    public Object visitLiteralExpression(final LiteralExpression literalExpression,
                                         final Scope scope) {
        final var loader = compilerContext.getClassElementLoader();
        final var types = loader.getTypes();

        final TypeMirror type = switch (literalExpression.getLiteralKind()) {
            case NULL -> types.getNullType();
            case CLASS -> loader.resolveType(Constants.CLAZZ);
            case STRING -> loader.resolveType(Constants.STRING);
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
        };

        literalExpression.setType(type);

        return null;
    }

    @Override
    public Object visitLambdaExpression(final CLambdaExpression lambdaExpression,
                                        final Scope scope) {
        final var lambdaType = lambdaExpression.getType();

        if (lambdaType instanceof ClassType classType) {
            final var classElement = (ClassSymbol) classType.asElement();
            final var qualifiedName = classElement.getQualifiedName();
            final var transformer = getTransformer(qualifiedName);

            if (transformer != null) {
                lambdaExpression.getVariables().forEach(variable ->
                        variable.accept(this, scope));
                lambdaExpression.accept(transformer, scope);
            }
        }

        return super.visitLambdaExpression(lambdaExpression, scope);
    }

    private AbstractTreeVisitor<?, Scope> getTransformer(final String qualifiedName) {
        final var transformerClass = this.transformers.get(qualifiedName);

        if (transformerClass == null) {
            return null;
        } else {
            try {
                final var constructor = transformerClass.getConstructor(
                        CompilerContext.class
                );
                return constructor.newInstance(compilerContext);
            } catch (Exception e) {
                return null;
            }
        }
    }
}