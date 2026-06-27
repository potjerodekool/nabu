package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.type.ExecutableType;

public record ApplicableMethod(ExecutableType method,
                               int phase,
                               double specificity) {
}
