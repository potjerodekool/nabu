package io.github.potjerodekool.nabu.compiler.ast.element;

public interface ConstantAttribute extends Attribute {

    @Override
    default <R, P> R accept(final AnnotationValueVisitor<R, P> v, final P p) {
        return switch (getValue()) {
            case Boolean b -> v.visitBoolean(b, p);
            case Byte b -> v.visitByte(b, p);
            case Character c -> v.visitChar(c, p);
            case Double d -> v.visitDouble(d, p);
            case Float f -> v.visitFloat(f, p);
            case Integer i -> v.visitInt(i, p);
            case Long l -> v.visitLong(l, p);
            case Short s -> v.visitShort(s, p);
            case String s -> v.visitString(s, p);
            default -> null;
        };
    }
}
