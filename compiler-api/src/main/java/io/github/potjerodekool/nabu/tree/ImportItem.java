package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;

public interface ImportItem extends Tree {

    boolean isStatic();

    FieldAccessExpressionTree getQualified();

    Element getSymbol();

    void setSymbol(Element symbol);

    boolean isStarImport();

    String getClassOrPackageName();
}
