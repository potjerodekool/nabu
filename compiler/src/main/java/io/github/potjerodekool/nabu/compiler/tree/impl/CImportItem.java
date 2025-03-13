package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.tree.ImportItem;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public abstract class CImportItem extends CTree implements ImportItem {

    public CImportItem(final int lineNumber,
                       final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitImportItem(this, param);
    }
}
