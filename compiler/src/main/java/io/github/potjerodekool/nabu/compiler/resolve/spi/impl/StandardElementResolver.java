package io.github.potjerodekool.nabu.compiler.resolve.spi.impl;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

public class StandardElementResolver implements ElementResolver {
    @Override
    public Element resolve(final String name,
                           final TypeMirror searchType) {
        if (!(searchType instanceof DeclaredType declaredType)) {
            return null;
        }

        var current = (TypeElement) declaredType.asElement();
        var hops = 0;

        while (current != null && hops++ < 20) {
            final var field = ElementFilter.fieldsIn(current.getEnclosedElements()).stream()
                    .filter(elem -> elem.getSimpleName().equals(name))
                    .findFirst();

            if (field.isPresent()) {
                return field.get();
            }

            final var superclass = current.getSuperclass();

            if (superclass instanceof DeclaredType superType) {
                current = (TypeElement) superType.asElement();
            } else {
                current = null;
            }
        }

        return null;
    }

    @Override
    public boolean supports(final TypeMirror searchType, final CompilerContext compilerContext, final Scope scope) {
        return true;
    }
}