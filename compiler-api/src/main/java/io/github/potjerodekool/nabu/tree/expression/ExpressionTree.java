package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.type.TypeMirror;

public interface ExpressionTree extends Tree {

    Element getSymbol();

    void setSymbol(Element symbol);

    TypeMirror getType();

    void setType(TypeMirror type);

}
