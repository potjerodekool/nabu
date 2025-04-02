package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.ErrorSymbol;
import io.github.potjerodekool.nabu.compiler.type.ErrorType;

import java.util.List;

public class CErrorType extends CClassType implements ErrorType {


    public CErrorType(final ErrorSymbol symbol) {
        super(null, symbol, List.of());
    }

    @Override
    public String getClassName() {
        return asElement().getQualifiedName();
    }
}
