package io.github.potjerodekool.nabu.compiler.tree;

public class SingleImportItem extends ImportItem {

    private final String className;

    public SingleImportItem(final String className) {
        this.className = className;
    }

    public String getClassName() {
        return className;
    }
}
