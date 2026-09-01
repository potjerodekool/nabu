package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.Objects;

public record ApplicableMethod(ExecutableType method,
                               int phase,
                               double specificity) {

    public ApplicableMethod {
        Objects.requireNonNull(method);
    }
}
