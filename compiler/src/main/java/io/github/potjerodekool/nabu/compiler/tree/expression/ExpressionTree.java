package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public interface ExpressionTree extends Tree {

    Element getSymbol();

    void setSymbol(Element symbol);

    TypeMirror getType();

    void setType(TypeMirror type);

}
