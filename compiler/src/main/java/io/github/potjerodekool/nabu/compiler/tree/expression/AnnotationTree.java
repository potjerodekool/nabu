package io.github.potjerodekool.nabu.compiler.tree.expression;

import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

import java.util.ArrayList;
import java.util.List;

public class AnnotationTree extends ExpressionTree {

    private final IdentifierTree name;
    private final List<ExpressionTree> arguments = new ArrayList<>();

    public AnnotationTree(final IdentifierTree name) {
        this(name, List.of());
    }

    public AnnotationTree(final IdentifierTree name,
                          final List<ExpressionTree> arguments) {
        this.name = name;
        this.arguments.addAll(arguments);
    }


    public IdentifierTree getName() {
        return name;
    }

    public List<ExpressionTree> getArguments() {
        return arguments;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitAnnotation(this, param);
    }
}
