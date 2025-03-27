package io.github.potjerodekool.nabu.compiler.ast.symbol;

import io.github.potjerodekool.nabu.compiler.ast.element.AnnotationMirror;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;

public final class AnnotationUtils {

    private AnnotationUtils() {
    }

    public static <A extends Annotation> A proxy(final AnnotationMirror annotationMirror,
                                                 final ClassLoader classLoader) {
        final var className = annotationMirror.getAnnotationType().asTypeElement().getQualifiedName();
        final Class<A> clazz = loadClass(className, classLoader);
        return (A) Proxy.newProxyInstance(
                AnnotationUtils.class.getClassLoader(),
                new Class[]{clazz},
                new AnnotationInvocationHandler(annotationMirror, classLoader)
        );
    }

    public static <T> Class<T> loadClass(final String className,
                                         final ClassLoader classLoader) {
        try {
            if (classLoader != null) {
                return (Class<T>) classLoader.loadClass(className);
            } else {
                return (Class<T>) ClassLoader.getSystemClassLoader().loadClass(className);
            }
        } catch (final ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
