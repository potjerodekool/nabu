package io.github.potjerodekool.nabu.compiler.ast.element;

import java.lang.annotation.Annotation;
import java.util.List;

public interface AnnotatedConstruct {

    List<? extends AnnotationMirror> getAnnotationMirrors();

    <A extends Annotation> A getAnnotation(Class<A> annotationType);

    <A extends Annotation> A[] getAnnotationsByType(Class<A> annotationType);
}
