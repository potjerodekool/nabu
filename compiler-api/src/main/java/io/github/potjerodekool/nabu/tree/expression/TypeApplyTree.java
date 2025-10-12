package io.github.potjerodekool.nabu.tree.expression;

import java.util.List;

public interface TypeApplyTree extends ExpressionTree {

    ExpressionTree getClazz();

    List<ExpressionTree> getTypeParameters();

}
