package io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Type;

/**
 * The {@link AnnotatedType} of a return type, parameter or field in a native executable: its (generic) type and the
 * annotations of a parameter (the type-use annotations of a parameter type are recorded as parameter annotations, see
 * BytecodeReachabilityCompiler). Spring asks for jspecify's {@code @Nullable} this way.
 */
public final class SimpleAnnotatedType implements AnnotatedType {

    private static final Annotation[] NONE = new Annotation[0];

    private final Type type;
    private final Annotation[] annotations;

    public SimpleAnnotatedType(final Type type) {
        this(type, NONE);
    }

    public SimpleAnnotatedType(final Type type, final Annotation[] annotations) {
        this.type = type;
        this.annotations = annotations != null ? annotations : NONE;
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
        for (final Annotation annotation : annotations) {
            if (annotationClass.isInstance(annotation)) {
                return annotationClass.cast(annotation);
            }
        }
        return null;
    }

    @Override
    public Annotation[] getAnnotations() {
        return annotations.clone();
    }

    @Override
    public Annotation[] getDeclaredAnnotations() {
        return annotations.clone();
    }
}
