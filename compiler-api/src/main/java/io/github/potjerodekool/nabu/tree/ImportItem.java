package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.impl.CImportItemTree;

/**
 * Root interfaces for import items.
 */
public sealed interface ImportItem extends Tree permits CImportItemTree {

    boolean isStatic();

    /**
     * @return Returns true for a module import declaration ({@code import module java.base;}): then
     * {@link #getQualified()} is the name of the module.
     */
    boolean isModule();

    FieldAccessExpressionTree getQualified();

    Element getSymbol();

    void setSymbol(Element symbol);

    boolean isStarImport();

    String getClassOrPackageName();
}
