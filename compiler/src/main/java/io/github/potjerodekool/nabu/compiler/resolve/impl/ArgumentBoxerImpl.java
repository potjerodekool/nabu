package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.resolve.ArgumentBoxer;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;

import java.util.ArrayList;

import static io.github.potjerodekool.nabu.util.CollectionUtils.forEachIndexed;

public class ArgumentBoxerImpl implements ArgumentBoxer {

    private static int BOX_ARGS_DEPTH = 0;

    private final Boxer boxer;

    public ArgumentBoxerImpl(final CompilerContext compilerContext) {
        this.boxer = new Boxer(compilerContext);
    }

    @Override
    public void boxArguments(final MethodInvocationTree methodInvocation) {
        if (BOX_ARGS_DEPTH > 40) {
            return;
        }

        BOX_ARGS_DEPTH++;
        try {
            boxArgumentsInternal(methodInvocation);
        } finally {
            BOX_ARGS_DEPTH--;
        }
    }

    private void boxArgumentsInternal(final MethodInvocationTree methodInvocation) {
        final var methodType = methodInvocation.getMethodType();

        final var arguments = methodInvocation.getArguments();
        final var argTypes = methodType.getParameterTypes();
        final var newArgs = new ArrayList<ExpressionTree>();
        final var parameterCount = argTypes.size();

        forEachIndexed(arguments,
                (i, arg) -> {
                    if (parameterCount == 0) {
                        newArgs.add(arg);
                        return;
                    }
                    if (arg.getLineNumber() < 0) {
                        newArgs.add(arg);
                        return;
                    }
                    final var argType = argTypes.get(Math.min(i, parameterCount - 1));
                    arg = argType.accept(boxer, arg);
                    newArgs.add(arg);
                }
        );

        methodInvocation.getArguments().clear();
        methodInvocation.setArguments(newArgs);
    }
}
