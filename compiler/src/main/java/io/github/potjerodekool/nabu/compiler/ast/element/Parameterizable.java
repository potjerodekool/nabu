package io.github.potjerodekool.nabu.compiler.ast.element;

import java.util.List;

public interface Parameterizable extends Element {

    List<? extends TypeParameterElement> getTypeParameters();
}
