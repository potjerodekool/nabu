package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.TypeSymbol;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.type.ErrorType;
import io.github.potjerodekool.nabu.type.TypeMirror;

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
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    @Override
    public List<TypeMirror> getTypeArguments() {
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
