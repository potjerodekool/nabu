package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

import io.github.potjerodekool.nabu.lang.model.element.AnnotationValue;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.resolve.AbstractAnnotationValueVisitor;
import io.github.potjerodekool.nabu.type.ArrayType;
import io.github.potjerodekool.nabu.type.DeclaredType;

import java.lang.reflect.Array;
import java.util.List;
import java.util.function.IntFunction;

public class ValueCollector extends AbstractAnnotationValueVisitor<Object, ExecutableElement> {

    private final ClassLoader classLoader;

    public ValueCollector() {
        this(AnnotationUtils.getAnnotationProcessorClassLoader());
    }

    public ValueCollector(final ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

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
        final var className = type.asTypeElement().getQualifiedName();
        final var constantName = c.getSimpleName();
        return Enum.valueOf(loadAnnotationClass(className), constantName);
    }

    public <T extends Enum<T>> Class<? extends T> loadAnnotationClass(final String className) {
        return AnnotationUtils.loadClass(className, classLoader);
    }

    @Override
    public Object visitArray(final List<? extends AnnotationValue> values, final ExecutableElement executableElement) {
        final var returnType = (ArrayType) executableElement.getReturnType();
        final var componentType = returnType.getComponentType();

        final var className = componentType.asTypeElement().getQualifiedName();
        final Class<?> elementType = AnnotationUtils.loadClass(className, classLoader);

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
