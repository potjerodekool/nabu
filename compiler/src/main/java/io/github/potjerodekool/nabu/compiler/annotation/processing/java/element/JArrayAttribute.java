package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.lang.model.element.Attribute;
import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;
import io.github.potjerodekool.nabu.tools.TodoException;

import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.AnnotationValueVisitor;
import java.util.List;

public class JArrayAttribute extends JAttribute {

    private final Attribute original;
    private List<AnnotationValue> annotationValues;

    public JArrayAttribute(final Attribute original) {
        this.original = original;
    }

    @Override
    public List<? extends AnnotationValue> getValue() {
        if (annotationValues == null) {
            final var list = (List<io.github.potjerodekool.nabu.lang.model.element.AnnotationValue>) original.getValue();
            annotationValues =list.stream()
                    .map(ElementWrapperFactory::wrap)
                    .toList();
        }
        return annotationValues;
    }

    @Override
    public <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return v.visitArray(getValue(), p);
    }
}
