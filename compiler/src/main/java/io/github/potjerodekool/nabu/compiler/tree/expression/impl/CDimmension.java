package io.github.potjerodekool.nabu.compiler.tree.expression.impl;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.Dimension;
import io.github.potjerodekool.nabu.compiler.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.ExpressionBuilder;

import java.util.ArrayList;
import java.util.List;

public class CDimmension extends CExpressionTree implements Dimension  {

    private final List<AnnotationTree> annotations;

    public CDimmension(final List<AnnotationTree> annotations,
                       final int lineNumber,
                       final int columnNumber) {
        super(lineNumber, columnNumber);
        this.annotations = new ArrayList<>(annotations);
    }

    @Override
    public List<AnnotationTree> getAnnotations() {
        return annotations;
    }

    public <E extends ExpressionTree, EB extends ExpressionBuilder<E, EB>> CDimmension(final ExpressionBuilder<E, EB> builder) {
        super(builder);
        //TODO Set annotations
        throw new TodoException();
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        throw new TodoException();
    }
}
