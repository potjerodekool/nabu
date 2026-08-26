package io.github.potjerodekool.nabu.tree.element.impl;

import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.element.ExportsTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of ExportsTree.
 */
public final class CExportsTree extends CDirective implements ExportsTree {

    private final IdentifierTree packageName;
    private final List<ExpressionTree> moduleNames;

    public CExportsTree(final IdentifierTree packageName,
                        final List<ExpressionTree> moduleNames,
                        final int lineNumber,
                        final int columnNumber) {
        super(lineNumber, columnNumber);
        this.packageName = packageName;
        this.moduleNames = new ArrayList<>(moduleNames);
    }

    @Override
    public ExpressionTree getPackageName() {
        return packageName;
    }

    @Override
    public List<? extends ExpressionTree> getModuleNames() {
        return moduleNames;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitExports(this, param);
    }
}
