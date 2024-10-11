package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import java.util.HashMap;
import java.util.Map;

public class LambdaContext {

    private final Map<String, Integer> lambdaCounters = new HashMap<>();

    LambdaContext() {
    }

    String generateLambdaMethodName(final String functionName) {
        final var counter =
                lambdaCounters.compute(functionName, (key, currentValue) ->
                        currentValue != null ? currentValue + 1 : 0
                );

        return String.format("lambda$%s$%s", functionName, counter);
    }
}
