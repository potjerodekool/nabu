package io.github.potjerodekool.nabu.lang.model.element.builder;

import io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;

import java.util.List;

/**
  Root interface for building elements.
 * @param <EB> Type of Element builder.
 */
public interface ElementBuilder<EB extends ElementBuilder<EB>>  {

    EB annotations(AnnotationMirror... annotations);

    EB annotations(List<AnnotationMirror> annotations);

    List<? extends AnnotationMirror> getAnnotations();

    Element getEnclosingElement();

    EB enclosingElement(Element enclosingElement);

    String getSimpleName();

    EB simpleName(String name);

    Element build();

    EB kind(ElementKind elementKind);

    ElementKind getKind();

    EB flags(long flags);

    long getFlags();

    EB enclosedElement(Element element);
}
