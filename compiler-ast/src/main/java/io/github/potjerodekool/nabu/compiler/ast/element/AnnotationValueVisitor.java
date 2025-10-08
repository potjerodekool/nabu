package io.github.potjerodekool.nabu.compiler.ast.element;

import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.List;

public interface AnnotationValueVisitor<R, P> {

    default R visit(AnnotationValue av, P p) {
        return av.accept(this, p);
    }

    default R visit(AnnotationValue av) {
        return visit(av, null);
    }

    R visitBoolean(boolean b, P p);

    R visitByte(byte b, P p);

    R visitChar(char c, P p);

    R visitDouble(double d, P p);

    R visitFloat(float f, P p);

    R visitInt(int i, P p);

    R visitLong(long l, P p);

    R visitShort(short s, P p);

    R visitString(String s, P p);

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
