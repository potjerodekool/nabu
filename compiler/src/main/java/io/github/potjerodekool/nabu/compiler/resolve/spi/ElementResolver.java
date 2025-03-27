package io.github.potjerodekool.nabu.compiler.resolve.spi;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public interface ElementResolver {

    Element resolve(String name,
                    TypeMirror searchType);

    boolean supports(TypeMirror searchType, final CompilerContext compilerContext, final Scope scope);
}
