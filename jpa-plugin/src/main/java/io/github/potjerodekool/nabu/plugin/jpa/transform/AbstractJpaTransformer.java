package io.github.potjerodekool.nabu.plugin.jpa.transform;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.resolve.AbstractResolver;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public abstract class AbstractJpaTransformer extends AbstractResolver {

    private static final String PATH_CLASS = "jakarta.persistence.criteria.Path";

    private final ClassElementLoader loader;

    protected AbstractJpaTransformer(final CompilerContext compilerContext) {
        super(compilerContext);
        this.loader = compilerContext.getClassElementLoader();
    }

    protected MethodInvocationTree createBuilderCall(final List<IdentifierTree> typeArguments,
                                                     final IdentifierTree builderIdentifier,
                                                     final String operatorMethodName,
                                                     final ExpressionTree... arguments) {
        final var lastArgument = arguments[arguments.length - 1];
        final MethodInvocationTree builderCall;

        if (isNullLiteral(lastArgument)) {
            final var methodName = "equal".equals(operatorMethodName)
                    ? "isNull"
                    : "isNotNull";
            builderCall = new MethodInvocationTree()
                    .target(builderIdentifier)
                    .name(new IdentifierTree(methodName))
                    .arguments(arguments[0]);
        } else {
            builderCall = new MethodInvocationTree()
                    .target(builderIdentifier)
                    .name(new IdentifierTree(operatorMethodName))
                    .arguments(arguments);
        }

        builderCall.typeArguments(typeArguments);

        resolveMethodCall(builderCall);

        compilerContext.getArgumentBoxer()
                .boxArguments(builderCall);

        return builderCall;
    }

    protected MethodInvocationTree createBuilderCall(final IdentifierTree builderIdentifier,
                                                     final String operatorMethodName,
                                                     final ExpressionTree... arguments) {
        return createBuilderCall(
                List.of(),
                builderIdentifier,
                operatorMethodName,
                arguments
        );
    }

    private boolean isNullLiteral(final ExpressionTree expression) {
        if (expression instanceof LiteralExpressionTree literalExpression) {
            return literalExpression.getLiteralKind() == LiteralExpressionTree.Kind.NULL;
        } else {
            return false;
        }
    }

    private void resolveMethodCall(final MethodInvocationTree methodInvocation) {
        final var resolvedMethodType = this.compilerContext.getMethodResolver().resolveMethod(methodInvocation);

        if (resolvedMethodType == null) {
            throw new TodoException();
        } else {
            methodInvocation.setMethodType(resolvedMethodType);
        }
    }

    protected TypeMirror resolvePathType() {
        return loader.resolveClass(PATH_CLASS).asType();
    }

    @Override
    public Object visitVariableType(final VariableTypeTree variableType, final Scope scope) {
        super.visitVariableType(variableType, scope);
        return variableType;
    }

    @Override
    public Object visitEmptyStatement(final EmptyStatementTree emptyStatementTree, final Scope param) {
        return emptyStatementTree;
    }

    @Override
    public Object visitIfStatement(final IfStatementTree ifStatementTree, final Scope param) {
        return ifStatementTree;
    }

    @Override
    public Object visitPrimitiveType(final PrimitiveTypeTree primitiveType, final Scope param) {
        return primitiveType;
    }

    @Override
    public Object visitForStatement(final ForStatement forStatement, final Scope param) {
        return forStatement;
    }

    @Override
    public Object visitWhileStatement(final WhileStatement whileStatement, final Scope scope) {
        return whileStatement;
    }

    @Override
    public Object visitDoWhileStatement(final DoWhileStatement doWhileStatement, final Scope scope) {
        return doWhileStatement;
    }
}
