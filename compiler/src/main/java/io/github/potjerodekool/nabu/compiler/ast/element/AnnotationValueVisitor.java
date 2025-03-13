package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public interface AnnotationValueVisitor<R, P> {

    default R visit(AnnotationValue av, P p) {
        return av.accept(this, p);
    }

    default R visit(AnnotationValue av) {
        return visit(av, null);
    }

    default R visitBoolean(boolean b, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(b), p);
    }

    default R visitByte(byte b, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(b), p);
    }

    default R visitChar(char c, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(c), p);
    }

    default R visitDouble(double d, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(d), p);
    }

    default R visitFloat(float f, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(f), p);
    }

    default R visitInt(int i, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(i), p);
    }

    default R visitLong(long l, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(l), p);
    }

    default R visitShort(short s, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(s), p);
    }

    default R visitString(String s, P p) {
        return visitUnknown(AnnotationBuilder.createConstantValue(s), p);
    }

    default R visitType(TypeMirror t, P p) {
        return visitUnknown(null, p);
    }

    default R visitEnumConstant(VariableElement c, P p) {
        return visitUnknown(null, p);
    }

    default R visitAnnotation(AnnotationMirror a, P p) {
        return visitUnknown(null, p);
    }

    default R visitArray(List<? extends AnnotationValue> values, P p) {
        return visitUnknown(null, p);
    }

    R visitUnknown(AnnotationValue av, P p);
}
