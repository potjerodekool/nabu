package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.log.LogLevel;
import io.github.potjerodekool.nabu.log.Logger;

import javax.lang.model.element.AnnotationValueVisitor;

public abstract class JAttribute implements JAnnotationValue {

    private final Logger logger = Logger.getLogger(getClass().getName());

    @Override
    public <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        logger.log(LogLevel.ERROR, "accept");
        throw new UnsupportedOperationException();
    }
}
