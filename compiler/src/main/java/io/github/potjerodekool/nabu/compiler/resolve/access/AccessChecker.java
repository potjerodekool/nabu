package io.github.potjerodekool.nabu.compiler.resolve.access;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;

public interface AccessChecker {
    boolean isAccessible(Element element, TypeElement classSymbol);
}
