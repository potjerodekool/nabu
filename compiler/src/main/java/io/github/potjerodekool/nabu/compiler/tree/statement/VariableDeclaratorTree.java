package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.VariableDeclaratorTreeBuilder;

import java.util.List;

public interface VariableDeclaratorTree extends StatementTree {

    Kind getKind();

    List<? extends AnnotationTree> getAnnotations();

    long getFlags();

    boolean hasFlag(final long flag);

    ExpressionTree getType();

    IdentifierTree getName();

    ExpressionTree getNameExpression();

    Tree getValue();

    VariableDeclaratorTreeBuilder builder();

}
