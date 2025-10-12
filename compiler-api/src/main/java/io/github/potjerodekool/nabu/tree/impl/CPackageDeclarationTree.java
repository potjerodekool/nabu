package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.tree.PackageDeclaration;
import io.github.potjerodekool.nabu.tree.TreeVisitor;
import io.github.potjerodekool.nabu.tree.expression.AnnotationTree;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;

import java.util.ArrayList;
import java.util.List;

public class CPackageDeclarationTree extends CTree implements PackageDeclaration {

    private final List<AnnotationTree> annotations = new ArrayList<>();

    private final ExpressionTree identifier;

    private PackageElement packageElement;

    public CPackageDeclarationTree(final List<AnnotationTree> annotations,
                                   final ExpressionTree identifier,
                                   final int line,
                                   final int columnNumber) {
        super(line, columnNumber);
        this.annotations.addAll(annotations);
        this.identifier = identifier;
    }

    @Override
    public ExpressionTree getIdentifier() {
        return identifier;
    }

    @Override
    public List<AnnotationTree> getAnnotations() {
        return annotations;
    }

    @Override
    public PackageElement getPackageElement() {
        return packageElement;
    }

    @Override
    public void setPackageElement(final PackageElement packageElement) {
        this.packageElement = packageElement;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitPackageDeclaration(this, param);
    }

    @Override
    public String getQualifiedName() {
        return getIdentifier().toString();
    }

}
