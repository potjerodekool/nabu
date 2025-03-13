package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.type.ArrayType;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.Types;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.Predicate;

public class ClassSymbol extends TypeSymbol implements TypeElement {

    private final NestingKind nestingKind;

    private String qualifiedName;

    private TypeMirror supeclass;

    private final List<TypeMirror> interfaces = new ArrayList<>();

    private final List<TypeParameterElement> typeParameters = new ArrayList<>();

    public ClassSymbol(final ElementKind kind,
                       final NestingKind nestingKind,
                       final long flags,
                       final String name,
                       final Element owner,
                       final List<AnnotationMirror> annotations) {
        super(kind, flags, name, owner);
        this.nestingKind = nestingKind;
        setAnnotations(annotations);
    }

    @Override
    public NestingKind getNestingKind() {
        return nestingKind;
    }

    private void resolveQualifiedName() {
        if (qualifiedName != null) {
            return;
        }

        final var enclosing = getEnclosingElement();

        if (enclosing != null) {
            final var enclosingName = enclosing instanceof QualifiedNameable qn
                    ? qn.getQualifiedName()
                    : enclosing.getSimpleName();
            qualifiedName = enclosingName + "." + getSimpleName();
        } else {
            qualifiedName = getSimpleName();
        }
    }

    @Override
    public void setEnclosingElement(final Element enclosingElement) {
        this.qualifiedName = null;
        super.setEnclosingElement(enclosingElement);
    }

    @Override
    public String getQualifiedName() {
        resolveQualifiedName();
        return qualifiedName;
    }

    @Override
    public ExecutableElement findFunctionalMethod() {
        return getEnclosedElements().stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .map(element -> (ExecutableElement) element)
                .findFirst()
                .orElse(null);
    }

    @Override
    public TypeMirror getSuperclass() {
        return supeclass;
    }

    public void setSuperClass(final TypeMirror supeclass) {
        this.supeclass = supeclass;
    }

    @Override
    public List<TypeMirror> getInterfaces() {
        return interfaces;
    }

    public void addInterface(final TypeMirror interfaceType) {
        this.interfaces.add(interfaceType);
    }

    @Override
    public List<? extends TypeParameterElement> getTypeParameters() {
        return typeParameters;
    }

    public void addTypeParameter(final TypeParameterElement typeParameterElement) {
        this.typeParameters.add(typeParameterElement);
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitType(this, p);
    }

    @Override
    public <R, P> R accept(final SymbolVisitor<R, P> v, final P p) {
        return v.visitClass(this, p);
    }

    public TypeMirror erasure(final Types types) {
        if (erasureType == null) {
            final var type = asType();

            erasureType = new CClassType(
                    types.erasure(
                            type.getEnclosingType()
                    ),
                    this,
                    List.of()
            );
        }

        return erasureType;
    }

    @Override
    public <A extends Annotation> A getAnnotation(final Class<A> annotationType) {
        final var mirrorOptional = findAnnotationMirror(annotationType.getName());
        return mirrorOptional
                .map(mirror -> annotationType.cast(AnnotationUtils.proxy(mirror, annotationType.getClassLoader())))
                .orElse(null);
    }

    @Override
    public <A extends Annotation> A[] getAnnotationsByType(final Class<A> annotationType) {
        final var classloader = getClass().getClassLoader();

        return (A[]) findAnnotationMirrors(annotationType.getName()).stream()
                .map(annotationMirror -> annotationType.cast(AnnotationUtils.proxy(
                        annotationMirror,
                        classloader
                )))
                .toArray(Annotation[]::new);
    }

    private Predicate<AnnotationMirror> annotationMirrorFilter(final String className) {
        return annotationMirror -> {
            final var annotationType = annotationMirror.getAnnotationType();
            final var element = (TypeElement) annotationType.asElement();
            return className.equals(element.getQualifiedName());
        };
    }

    private Optional<? extends AnnotationMirror> findAnnotationMirror(final String className) {
        return getAnnotationMirrors().stream()
                .filter(annotationMirrorFilter(className)).findFirst();
    }

    private List<? extends AnnotationMirror> findAnnotationMirrors(final String className) {
        return getAnnotationMirrors().stream()
                .filter(annotationMirrorFilter(className))
                .toList();
    }

}

class ValueCollector implements AnnotationValueVisitor<Object, ExecutableElement> {

    @Override
    public Object visitBoolean(final boolean b, final ExecutableElement executableElement) {
        return b;
    }

    @Override
    public Object visitByte(final byte b, final ExecutableElement executableElement) {
        return b;
    }

    @Override
    public Object visitChar(final char c, final ExecutableElement executableElement) {
        return c;
    }

    @Override
    public Object visitDouble(final double d, final ExecutableElement executableElement) {
        return d;
    }

    @Override
    public Object visitFloat(final float f, final ExecutableElement executableElement) {
        return f;
    }

    @Override
    public Object visitInt(final int i, final ExecutableElement executableElement) {
        return i;
    }

    @Override
    public Object visitLong(final long l, final ExecutableElement executableElement) {
        return l;
    }

    @Override
    public Object visitShort(final short s, final ExecutableElement executableElement) {
        return s;
    }

    @Override
    public Object visitString(final String s, final ExecutableElement executableElement) {
        return s;
    }

    @Override
    public Object visitEnumConstant(final VariableElement c, final ExecutableElement executableElement) {
        final var type = (DeclaredType) c.asType();
        final var className = type.getTypeElement().getQualifiedName();
        final var constantName = c.getSimpleName();
        final Class<Enum> enumClass = AnnotationUtils.loadClass(className, getClass().getClassLoader());
        return Enum.valueOf(enumClass, constantName);
    }

    @Override
    public Object visitArray(final List<? extends AnnotationValue> values, final ExecutableElement executableElement) {
        final var returnType = (ArrayType) executableElement.getReturnType();
        final var componentType = returnType.getComponentType();

        final var className = componentType.getTypeElement().getQualifiedName();
        final Class<?> elementType = AnnotationUtils.loadClass(className, getClass().getClassLoader());

        return values.stream()
                .map(it -> it.accept(this, executableElement))
                .toArray(createArray(elementType));
    }

    private <R> IntFunction<R> createArray(final Class<?> elementType) {
        return value -> (R) Array.newInstance(elementType, value);
    }

    @Override
    public Object visitUnknown(final AnnotationValue av, final ExecutableElement o) {
        System.err.println("ClassSymbol.ValueCollector#visitUnknown " + av);
        return null;
    }
}

