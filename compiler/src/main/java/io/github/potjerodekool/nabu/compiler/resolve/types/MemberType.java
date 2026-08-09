package io.github.potjerodekool.nabu.compiler.resolve.types;

import io.github.potjerodekool.nabu.compiler.lang.model.element.Element;
import io.github.potjerodekool.nabu.type.TypeMirror;

public class MemberType extends SimpleVisitor<TypeMirror, Element> {

    public TypeMirror memberType(final TypeMirror typeMirror,
                                 final Element symbol) {
        return symbol.isStatic()
                ? symbol.asType()
                : this.visit(typeMirror, symbol);
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror,
                                       final Element param) {
        return typeMirror;
    }
}
