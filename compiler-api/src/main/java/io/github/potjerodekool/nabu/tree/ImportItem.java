package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.impl.CImportItemTree;

/**
 * Root interfaces for import items.
 */
public sealed interface ImportItem extends Tree permits CImportItemTree {

    boolean isStatic();

    FieldAccessExpressionTree getQualified();

    Element getSymbol();

    void setSymbol(Element symbol);

    boolean isStarImport();

    String getClassOrPackageName();
}
