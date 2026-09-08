package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

import io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror;

import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;

public final class AnnotationUtils {

    private static volatile ClassLoader annotationProcessorClassLoader;

    private AnnotationUtils() {
    }

    public static <A extends Annotation> A proxy(final AnnotationMirror annotationMirror,
                                                 final ClassLoader classLoader) {
        final var className = annotationMirror.getAnnotationType().asTypeElement().getQualifiedName();
        final Class<A> clazz = loadClass(className, classLoader);
        return (A) Proxy.newProxyInstance(
                clazz.getClassLoader(),
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

    public static void setAnnotationProcessorClassLoader(final ClassLoader classLoader) {
        if (classLoader != null) {
            annotationProcessorClassLoader = classLoader;
        }
    }

    public static ClassLoader getAnnotationProcessorClassLoader() {
        return annotationProcessorClassLoader;
    }
}