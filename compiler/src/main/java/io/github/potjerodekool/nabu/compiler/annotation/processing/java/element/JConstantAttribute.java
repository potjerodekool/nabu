package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.lang.model.element.ConstantAttribute;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.type.TypeMirror;

import javax.lang.model.element.AnnotationValueVisitor;

public class JConstantAttribute extends JAttribute {

    private final ConstantAttribute original;

    public JConstantAttribute(final ConstantAttribute original) {
        this.original = original;
    }

    @Override
    public Object getValue() {
        return original.getValue();
    }

    @Override
    public <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        final var value = getValue();

        return switch (value) {
            case Boolean booleanValue -> v.visitBoolean(booleanValue, p);
            case Byte byteValue -> v.visitByte(byteValue, p);
            case Character characterValue -> v.visitChar(characterValue, p);
            case Double doubleValue -> v.visitDouble(doubleValue, p);
            case Float floatValue -> v.visitFloat(floatValue, p);
            case Integer integerValue -> v.visitInt(integerValue, p);
            case Short shortValue -> v.visitShort(shortValue, p);
            case String stringValue -> v.visitString(stringValue, p);
            case TypeMirror typeValue -> v.visitType(TypeWrapperFactory.wrap(typeValue), p);
            default -> throw new TodoException();
        };
    }
}
