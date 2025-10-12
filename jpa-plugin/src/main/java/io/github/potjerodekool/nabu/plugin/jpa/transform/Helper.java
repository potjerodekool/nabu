package io.github.potjerodekool.nabu.plugin.jpa.transform;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;

public final class Helper {

    private static final String PATH_CLASS = "jakarta.persistence.criteria.Path";

    private Helper() {
    }

    public static MethodInvocationTree createBuilderCall(final CompilerContext compilerContext,
                                                         final List<IdentifierTree> typeArguments,
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
                    TreeMaker.fieldAccessExpressionTree(
                            builderIdentifier,
                            IdentifierTree.create(methodName),
                            -1,
                            -1
                    ),
                    typeArguments,
                    List.of(arguments[0]),
                    -1,
                    -1
            );

        } else {
            builderCall = TreeMaker
                    .methodInvocationTree(
                            TreeMaker.fieldAccessExpressionTree(
                                    builderIdentifier,
                                    IdentifierTree.create(operatorMethodName),
                                    -1,
                                    -1
                            ),
                            typeArguments,
                            List.of(arguments),
                            -1,
                            -1
                    );
        }

        resolveMethodCall(builderCall, compilerContext);

        compilerContext.getArgumentBoxer()
                .boxArguments(builderCall);

        return builderCall;
    }

    public static MethodInvocationTree createBuilderCall(final CompilerContext compilerContext,
                                                         final IdentifierTree builderIdentifier,
                                                         final String operatorMethodName,
                                                         final ExpressionTree... arguments) {
        return createBuilderCall(
                compilerContext,
                List.of(),
                builderIdentifier,
                operatorMethodName,
                arguments
        );
    }

    private static boolean isNullLiteral(final ExpressionTree expression) {
        if (expression instanceof LiteralExpressionTree literalExpression) {
            return literalExpression.getLiteralKind() == LiteralExpressionTree.Kind.NULL;
        } else {
            return false;
        }
    }

    private static void resolveMethodCall(final MethodInvocationTree methodInvocation,
                                          final CompilerContext compilerContext) {
        final var resolvedMethodTypeOptional = compilerContext.getMethodResolver().resolveMethod(methodInvocation);

        resolvedMethodTypeOptional.ifPresent(resolvedMethodType -> {
            final var type = resolvedMethodType.getOwner().asType();
            methodInvocation.getMethodSelector().setType(type);
            methodInvocation.setMethodType(resolvedMethodType);
        });
    }

    public static TypeMirror resolvePathType(final ClassElementLoader loader,
                                             final Scope scope) {
        return loader.loadClass(scope.findModuleElement(), PATH_CLASS).asType();
    }
}
