package io.github.potjerodekool.nabu.compiler.type.immutable;

import io.github.potjerodekool.nabu.compiler.type.ErrorType;

import java.util.List;

public class ImmutableErrorType extends ImmutableClassType implements ErrorType {

    private final String className;

    public ImmutableErrorType(final String className) {
        super(null, null, List.of());
        this.className = className;
    }

    @Override
    public String getClassName() {
        return className;
    }
}
