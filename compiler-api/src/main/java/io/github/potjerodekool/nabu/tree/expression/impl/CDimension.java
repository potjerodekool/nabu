package io.github.potjerodekool.nabu.tree.expression.impl;

import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.Dimension;
import io.github.potjerodekool.nabu.tree.expression.builder.ExpressionBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of Dimension.
 */
public class CDimension extends CExpressionTree implements Dimension  {

    private final List<AnnotationTree> annotations;

    public CDimension(final List<AnnotationTree> annotations,
                      final int lineNumber,
                      final int columnNumber) {
        super(lineNumber, columnNumber);
        this.annotations = new ArrayList<>(annotations);
    }

    @Override
    public List<AnnotationTree> getAnnotations() {
        return annotations;
    }

    public <EB extends ExpressionBuilder<EB>> CDimension(final ExpressionBuilder<EB> builder) {
        super(builder);
        //TODO Set annotations
        throw new TodoException();
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        throw new TodoException();
    }
}
