package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.type.ErrorType;

import java.util.List;

public class CErrorType extends CClassType implements ErrorType {

    private final String className;

    public CErrorType(final ClassSymbol symbol) {
        super(null, symbol, List.of());
        this.className = symbol.getQualifiedName();
    }

    @Override
    public String getClassName() {
        return className;
    }
}
