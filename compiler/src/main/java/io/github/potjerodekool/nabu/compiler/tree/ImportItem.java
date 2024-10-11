package io.github.potjerodekool.nabu.compiler.tree;

public abstract class ImportItem extends Tree {

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitImportItem(this, param);
    }
}
