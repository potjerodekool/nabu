package io.github.potjerodekool.nabu.lang.model.element;

import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;

/**
 * A visitor to visit annotation values.
 * @param <R> Return type
 * @param <P> Parameter type
 */
public interface AnnotationValueVisitor<R, P> {

    /**
     * @param av Annotation value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    default R visit(AnnotationValue av, P p) {
        return av.accept(this, p);
    }

    /**
     * @param av Annotation value.
     * @return Returns some result.
     */
    default R visit(AnnotationValue av) {
        return visit(av, null);
    }

    /**
     * @param b Boolean value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    R visitBoolean(boolean b, P p);

    /**
     * @param b Byte value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    R visitByte(byte b, P p);

    /**
     * @param c Character value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    R visitChar(char c, P p);

    /**
     * @param d Double value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    R visitDouble(double d, P p);

    /**
     * @param f Float value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    R visitFloat(float f, P p);

    /**
     * @param i Integer value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    R visitInt(int i, P p);

    /**
     * @param l Long value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    R visitLong(long l, P p);

    /**
     * @param s Short value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    R visitShort(short s, P p);

    /**
     * @param s String value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    R visitString(String s, P p);

    /**
     * @param t TypeMirror value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    default R visitType(TypeMirror t, P p) {
        return visitUnknown(null, p);
    }

    /**
     * @param c Enum constant value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    default R visitEnumConstant(VariableElement c, P p) {
        return visitUnknown(null, p);
    }

    /**
     * @param a Annotation value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    default R visitAnnotation(AnnotationMirror a, P p) {
        return visitUnknown(null, p);
    }

    /**
     * @param values Array of values.
     * @param p Some parameter.
     * @return Returns some result.
     */
    default R visitArray(List<? extends AnnotationValue> values, P p) {
        return visitUnknown(null, p);
    }

    /**
     * @param av Annotation value.
     * @param p Some parameter.
     * @return Returns some result.
     */
    R visitUnknown(AnnotationValue av, P p);
}
