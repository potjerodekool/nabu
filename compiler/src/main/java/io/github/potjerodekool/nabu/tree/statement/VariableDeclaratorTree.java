package io.github.potjerodekool.nabu.tree.statement;

import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;

import java.util.List;

public interface VariableDeclaratorTree extends StatementTree {

    Kind getKind();

    List<? extends AnnotationTree> getAnnotations();

    long getFlags();

    boolean hasFlag(final long flag);

    ExpressionTree getVariableType();

    IdentifierTree getName();

    ExpressionTree getNameExpression();

    Tree getValue();

    VariableDeclaratorTreeBuilder builder();

}
