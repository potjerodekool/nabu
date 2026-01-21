package io.github.potjerodekool.nabu.compiler.impl;

public record ResultOrException<T>(T value,
                                   Exception exception) {

    public boolean hasResult() {
        return value != null;
    }

    public boolean hasException() {
        return exception != null;
    }

}
