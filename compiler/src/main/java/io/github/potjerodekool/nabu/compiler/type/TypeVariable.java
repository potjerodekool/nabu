package io.github.potjerodekool.nabu.compiler.type;

import io.github.potjerodekool.nabu.compiler.ast.element.AbstractSymbol;

public interface TypeVariable extends ReferenceType {

    AbstractSymbol asElement();

    TypeMirror getUpperBound();

    TypeMirror getLowerBound();
}
