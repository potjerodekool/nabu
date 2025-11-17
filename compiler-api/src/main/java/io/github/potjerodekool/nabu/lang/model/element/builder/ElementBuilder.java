package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;

/**
  Root interface for building elements.
 * @param <EB> Type of Element builder.
 */
public interface ElementBuilder<EB extends ElementBuilder<EB>>  {

    Element getEnclosingElement();

    EB enclosingElement(Element enclosingElement);

    String getSimpleName();

    EB simpleName(String name);

    Element build();

    EB kind(ElementKind elementKind);

    EB flags(long flags);

    EB enclosedElement(Element element);
}
