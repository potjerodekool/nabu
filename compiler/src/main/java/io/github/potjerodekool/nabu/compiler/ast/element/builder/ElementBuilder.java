package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;

public interface ElementBuilder<E extends Element, EB extends ElementBuilder<E, EB>>  {

    Element getEnclosingElement();

    EB enclosingElement(Element enclosedElement);

    EB simpleName(String name);

    E build();

    EB kind(ElementKind elementKind);

    EB flags(long flags);
}
