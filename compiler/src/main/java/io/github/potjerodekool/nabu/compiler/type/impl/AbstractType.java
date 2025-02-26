package io.github.potjerodekool.nabu.compiler.type.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public abstract class AbstractType implements TypeMirror {

    public Element asElement() {
        return null;
    }

    @Override
    public boolean equals(final Object obj) {
        return false;
    }

}
