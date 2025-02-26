package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.HashMap;

public class MemberOfVisitor implements TypeVisitor<TypeMirror, Element> {

    private final Types types;

    public MemberOfVisitor(final Types types) {
        this.types = types;
    }

    @Override
    public TypeMirror visitUnknownType(final TypeMirror typeMirror, final Element param) {
        throw new TodoException();
    }

    @Override
    public TypeMirror visitDeclaredType(final DeclaredType classType, final Element element) {
        final var typeArguments = classType.getTypeArguments();
        final var clazz = (TypeElement) classType.asElement();
        final var typeParameters = clazz.getTypeParameters();

        final var typeArgMap = new HashMap<String, TypeMirror>();

        for (var i = 0; i < typeParameters.size(); i++) {
            final var typeParameter = typeParameters.get(i);
            final var typeArgument = typeArguments.get(i);
            final var name = typeParameter.getSimpleName();
            typeArgMap.put(name, typeArgument);
        }

        final var applyer = new TypeArgApplyer(this.types);
        return element.accept(applyer, typeArgMap);
    }

}

