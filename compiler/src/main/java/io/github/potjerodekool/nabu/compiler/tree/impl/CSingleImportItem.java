package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.tree.SingleImportItem;

public class CSingleImportItem extends CImportItem implements SingleImportItem {

    private final String className;

    public CSingleImportItem(final String className,
                             final int lineNumber,
                             final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.className = className;
    }

    public String getClassName() {
        return className;
    }
}
