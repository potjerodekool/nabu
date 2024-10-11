package io.github.potjerodekool.nabu.compiler.transform;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.resolve.AbstractResolver;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;

public abstract class AbstractJpaTransformer extends AbstractResolver {

    protected AbstractJpaTransformer(final CompilerContext compilerContext) {
        super(compilerContext);
    }

    protected MethodInvocation createBuilderCall(final CIdent builderIdentifier,
                                                 final String operatorMethodName,
                                                 final CExpression... arguments) {
        final var lastArgument = arguments[arguments.length - 1];
        final MethodInvocation builderCall;

        if (isNullLiteral(lastArgument)) {
            final var methodName = "equal".equals(operatorMethodName)
                    ? "isNull"
                    : "isNotNull";
            builderCall = new MethodInvocation()
                    .target(builderIdentifier)
                    .name(new CIdent(methodName))
                    .arguments(arguments[0]);
        } else {
            builderCall = new MethodInvocation()
                    .target(builderIdentifier)
                    .name(new CIdent(operatorMethodName))
                    .arguments(arguments);
        }

        resolveMethodCall(builderCall);

        compilerContext.getArgumentBoxer()
                .boxArguments(builderCall);

        return builderCall;
    }

    private boolean isNullLiteral(final CExpression expression) {
        if (expression instanceof LiteralExpression literalExpression) {
            return literalExpression.getLiteralKind() == LiteralExpression.Kind.NULL;
        } else {
            return false;
        }
    }

    private void resolveMethodCall(final MethodInvocation methodInvocation) {
        final var resolvedMethodType = this.compilerContext.getMethodResolver().resolveMethod(methodInvocation);

        if (resolvedMethodType == null) {
            throw new TodoException();
        } else {
            methodInvocation.setMethodType(resolvedMethodType);
        }
    }

    @Override
    public Object visitVariableType(final CVariableType variableType, final Scope scope) {
        super.visitVariableType(variableType, scope);
        return variableType;
    }
}
