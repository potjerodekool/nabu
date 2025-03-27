package io.github.potjerodekool.nabu.plugin.jpa.transform;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.resolve.AbstractResolver;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public abstract class AbstractJpaTransformer extends AbstractResolver {

    private static final String PATH_CLASS = "jakarta.persistence.criteria.Path";

    private final CompilerContext compilerContext;
    private final ClassElementLoader loader;

    protected AbstractJpaTransformer(final CompilerContext compilerContext) {
        super(compilerContext);
        this.compilerContext = compilerContext;
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
            builderCall = TreeMaker.methodInvocationTree(
                    builderIdentifier,
                    IdentifierTree.create(methodName),
                    typeArguments,
                    List.of(arguments[0]),
                    -1,
                    -1
            );

        } else {
            builderCall = TreeMaker
                    .methodInvocationTree(
                            builderIdentifier,
                            IdentifierTree.create(operatorMethodName),
                            typeArguments,
                            List.of(arguments),
                            -1,
                            -1
                    );
        }

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
        final var resolvedMethodType = this.compilerContext.getMethodResolver().resolveMethod(methodInvocation, null);
        methodInvocation.setMethodType(resolvedMethodType);
    }

    protected TypeMirror resolvePathType(final Scope scope) {
        return loader.loadClass(scope.findModuleElement(), PATH_CLASS).asType();
    }

    @Override
    public Object visitUnknown(final Tree tree,
                               final Scope Param) {
        return tree;
    }
}
