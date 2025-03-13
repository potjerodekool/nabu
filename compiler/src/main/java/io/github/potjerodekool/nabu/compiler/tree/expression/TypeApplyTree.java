package io.github.potjerodekool.nabu.compiler.tree.expression;

import java.util.List;

public interface TypeApplyTree extends ExpressionTree, Identifier {

    List<ExpressionTree> getTypeParameters();

}
