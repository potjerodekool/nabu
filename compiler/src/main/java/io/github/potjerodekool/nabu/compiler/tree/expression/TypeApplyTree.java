package io.github.potjerodekool.nabu.compiler.tree.expression;

import java.util.List;

public interface TypeApplyTree extends ExpressionTree {

    ExpressionTree getClazz();

    List<ExpressionTree> getTypeParameters();

}
