package io.github.potjerodekool.nabu.compiler.tree.statement;

import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;

import java.util.List;

public interface VariableDeclarator extends Statement {

    Kind getKind();

    List<? extends AnnotationTree> getAnnotations();

    long getFlags();

    boolean hasFlag(final int flag);

    ExpressionTree getType();

    IdentifierTree getName();

    ExpressionTree getNameExpression();

    Tree getValue();

    VariableDeclaratorBuilder builder();

}
