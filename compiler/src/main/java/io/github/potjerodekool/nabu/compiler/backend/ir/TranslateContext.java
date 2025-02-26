package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.tree.element.Function;

import java.util.HashMap;
import java.util.Map;

public class TranslateContext {

    Function function;
    Frame frame;

    private final Map<String, Integer> lambdaCounters;

    TranslateContext() {
        this.lambdaCounters = new HashMap<>();
    }

    String generateLambdaMethodName(final String functionName) {
        final var counter =
                lambdaCounters.compute(functionName, (key, currentValue) ->
                        currentValue != null ? currentValue + 1 : 0
                );

        return String.format("lambda$%s$%s", functionName, counter);
    }
}
