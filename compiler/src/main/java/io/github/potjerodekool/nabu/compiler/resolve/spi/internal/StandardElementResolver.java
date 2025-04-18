package io.github.potjerodekool.nabu.compiler.resolve.spi.internal;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementFilter;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class StandardElementResolver implements ElementResolver {
    @Override
    public Element resolve(final String name,
                           final TypeMirror searchType) {
        if (searchType instanceof DeclaredType declaredType) {
            final var typeElement = (TypeElement) declaredType.asElement();

            return ElementFilter.fieldsIn(typeElement.getEnclosedElements()).stream()
                    .filter(elem -> elem.getSimpleName().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    @Override
    public boolean supports(final TypeMirror searchType, final CompilerContext compilerContext, final Scope scope) {
        return true;
    }
}
