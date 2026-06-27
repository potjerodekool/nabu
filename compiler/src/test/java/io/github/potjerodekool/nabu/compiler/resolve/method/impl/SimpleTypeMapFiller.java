package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SimpleTypeMapFiller {

    private SimpleTypeMapFiller() {
    }

    public static Map<String, TypeMirror> fill(final DeclaredType declaredType) {
        final var typeArgs = declaredType.getTypeArguments();

        if (typeArgs.isEmpty()) {
            return Collections.emptyMap();
        }

        final var typeElement = declaredType.asTypeElement();
        final var typeParameters = typeElement.getTypeParameters();

        final var count = typeArgs.size();
        final var map = new HashMap<String, TypeMirror>();

        for (var i = 0; i < count; i++) {
            final var typeArg = typeArgs.get(i);
            final var typeParameter = typeParameters.get(i);
            final var name = typeParameter.getSimpleName();


            map.put(name, typeArg);
        }

        return map;
    }
}
