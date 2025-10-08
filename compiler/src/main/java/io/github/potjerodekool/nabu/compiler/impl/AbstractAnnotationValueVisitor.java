package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.AnnotationValueVisitor;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.AnnotationBuilder;

public abstract class AbstractAnnotationValueVisitor<R, P> implements AnnotationValueVisitor<R, P> {

    public R visitBoolean(boolean b, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(b), p);
    }

    public R visitByte(byte b, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(b), p);
    }

    public R visitChar(char c, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(c), p);
    }

    public R visitDouble(double d, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(d), p);
    }

    public R visitFloat(float f, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(f), p);
    }

    public R visitInt(int i, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(i), p);
    }

    public R visitLong(long l, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(l), p);
    }

    public R visitShort(short s, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(s), p);
    }

    public R visitString(String s, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(s), p);
    }
}
