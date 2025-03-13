package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.QualifiedNameable;
import io.github.potjerodekool.nabu.compiler.tree.PackageDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;

import java.util.ArrayList;
import java.util.List;

public class CPackageDeclaration extends CTree implements PackageDeclaration {

    private final List<AnnotationTree> annotations = new ArrayList<>();

    private final String packageName;

    public CPackageDeclaration(final List<AnnotationTree> annotations,
                               final String packageName,
                               final int line,
                               final int charPositionInLine) {
        super(line, charPositionInLine);
        this.annotations.addAll(annotations);
        this.packageName = packageName;
    }

    public String getPackageName() {
        return packageName;
    }

    public List<AnnotationTree> getAnnotations() {
        return annotations;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitPackageDeclaration(this, param);
    }

    @Override
    public String getQualifiedName() {
        return packageName;
    }

}
