package io.github.potjerodekool.nabu.compiler.resolve.access;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;

public interface AccessChecker {
    boolean isAccessible(Element element, TypeElement classSymbol);
}
