package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.expression.FieldAccessExpressionTree;

public interface ImportItem extends Tree {

    boolean isStatic();

    FieldAccessExpressionTree getQualified();

    Element getSymbol();

    void setSymbol(Element symbol);

    boolean isStarImport();

    String getClassOrPackageName();
}
