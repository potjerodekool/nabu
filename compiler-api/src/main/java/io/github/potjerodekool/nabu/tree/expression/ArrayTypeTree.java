package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.expression.impl.CArrayTypeTree;

import java.util.List;

/**
 * Array type
 */
public interface ArrayTypeTree extends ExpressionTree {

    Tree getComponentType();

    List<Dimension> getDimensions();

    static ArrayTypeTree create(final Tree componentType,
                                final List<Dimension> dims,
                                final int lineNumber,
                                final int columnNumber) {
        return new CArrayTypeTree(componentType, dims,  lineNumber, columnNumber);
    }
}
