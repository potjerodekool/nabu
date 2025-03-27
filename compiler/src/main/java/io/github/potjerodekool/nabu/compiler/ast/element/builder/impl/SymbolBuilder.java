package io.github.potjerodekool.nabu.compiler.ast.element.builder.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ElementBuilder;

public interface SymbolBuilder<E extends Element, EB extends SymbolBuilder<E,EB>> extends ElementBuilder<E, EB> {

    EB flags(long l);
}
