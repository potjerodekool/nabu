package io.github.potjerodekool.nabu.resolve.spi;

import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.type.TypeMirror;

public interface ElementResolver {

    Element resolve(String name,
                    TypeMirror searchType);

    boolean supports(TypeMirror searchType,
                     CompilerContext compilerContext,
                     Scope scope);
}
