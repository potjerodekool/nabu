package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.tools.TodoException;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementVisitor;
import javax.lang.model.type.TypeMirror;
import java.lang.annotation.Annotation;
import java.util.List;

public class JTypeParameterElement extends JElement<TypeParameterElement> implements javax.lang.model.element.TypeParameterElement {

    private final Element genericElement;
    private final List<TypeMirror> bounds;

    protected JTypeParameterElement(final TypeParameterElement original) {
        super(original);
        this.genericElement = ElementWrapperFactory.wrap(original.getGenericElement());
        this.bounds = original.getBounds().stream()
                .map(TypeWrapperFactory::wrap)
                .toList();
    }

    @Override
    public Element getGenericElement() {
        return genericElement;
    }

    @Override
    public List<? extends TypeMirror> getBounds() {
        return bounds;
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitTypeParameter(this, p);
    }
}
