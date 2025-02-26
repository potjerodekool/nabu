package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public interface SymbolResolver {

    Element resolve(String name,
                    TypeMirror searchType);

    boolean supports(TypeMirror searchType);
}
