package io.github.potjerodekool.nabu.compiler.type;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;

public interface DeclaredType extends ReferenceType {

    Element asElement();

}
