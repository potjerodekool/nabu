package io.github.potjerodekool.nabu.tree.expression;

import io.github.potjerodekool.nabu.tree.Tree;

import java.util.List;

/**
 * Type apply.
 * <p> </p>
 * List&lt;String&gt;
 */
public interface TypeApplyTree extends ExpressionTree {

    ExpressionTree getClazz();

    List<Tree> getTypeParameters();

}
