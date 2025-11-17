package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.resolve.ArgumentBoxer;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;

import java.util.ArrayList;

import static io.github.potjerodekool.nabu.util.CollectionUtils.forEachIndexed;

public class ArgumentBoxerImpl implements ArgumentBoxer {

    private final Boxer boxer;

    public ArgumentBoxerImpl(final ClassElementLoader classElementLoader,
                             final MethodResolver methodResolver) {
        this.boxer = new Boxer(
                classElementLoader,
                methodResolver);
    }

    @Override
    public void boxArguments(final MethodInvocationTree methodInvocation) {
        final var methodType = methodInvocation.getMethodType();

        final var arguments = methodInvocation.getArguments();
        final var argTypes = methodType.getParameterTypes();
        final var newArgs = new ArrayList<ExpressionTree>();

        forEachIndexed(arguments,
                (i, arg) -> {
                    final var argType = argTypes.get(i);
                    arg = argType.accept(boxer, arg);
                    newArgs.add(arg);
                }
        );

        methodInvocation.getArguments().clear();
        methodInvocation.setArguments(newArgs);
    }
}
