package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.symbol.TypeSymbol;
import io.github.potjerodekool.nabu.compiler.type.ErrorType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public abstract class AbstractType implements TypeMirror {

    protected TypeSymbol element;

    protected AbstractType(final TypeSymbol typeElement) {
        setElement(typeElement);
    }

    public void setElement(final TypeSymbol element) {
        this.element = element;
    }

    @Override
    public boolean equals(final Object obj) {
        return false;
    }

    @Override
    public List<AbstractType> getTypeArguments() {
        return List.of();
    }

    public Element asElement() {
        return element;
    }

    public boolean isError() {
        if (this instanceof ErrorType) {
            return true;
        }

        return element.isError();
    }
}
