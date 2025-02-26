package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.tree.expression.*;

import java.util.ArrayList;

import static io.github.potjerodekool.nabu.compiler.util.CollectionUtils.forEachIndexed;

public class ArgumentBoxer{

    private final Boxer boxer;

    public ArgumentBoxer(final ClassElementLoader classElementLoader,
                         final MethodResolver methodResolver) {
        this.boxer = new Boxer(
                classElementLoader,
                methodResolver);
    }

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
        newArgs.forEach(methodInvocation::argument);
    }
}

