package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class StandardSymbolResolver implements SymbolResolver {
    @Override
    public Element resolve(final String name,
                           final TypeMirror searchType) {
        if (searchType instanceof DeclaredType classType) {
            final var classSymbol = (TypeElement) classType.asElement();

            return ElementFilter.fields(classSymbol).stream()
                    .filter(elem -> elem.getSimpleName().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    @Override
    public boolean supports(final TypeMirror searchType) {
        return true;
    }
}
