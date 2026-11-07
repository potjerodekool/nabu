package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Type;

/**
 * The {@link AnnotatedType} of a return type, parameter or field in a native executable: its (generic) type and no
 * type annotations. The class files' type annotations are not kept; code that asks for them (hibernate-validator
 * looks for constraints on type arguments) finds none.
 */
public final class SimpleAnnotatedType implements AnnotatedType {

    private final Type type;

    public SimpleAnnotatedType(final Type type) {
        this.type = type;
    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public AnnotatedType getAnnotatedOwnerType() {
        return null;
    }

    @Override
    public <T extends Annotation> T getAnnotation(final Class<T> annotationClass) {
        return null;
    }

    @Override
    public Annotation[] getAnnotations() {
        return new Annotation[0];
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return new Annotation[0];
    }
}
