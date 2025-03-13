package io.github.potjerodekool.nabu.compiler.tree.expression;

import java.util.List;

public interface NewArrayExpression extends ExpressionTree {

    List<ExpressionTree> getElements();

}
