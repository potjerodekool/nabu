package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.QualifiedNameable;
import io.github.potjerodekool.nabu.compiler.tree.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.expression.AnnotationTree;

import java.util.ArrayList;
import java.util.List;

public class PackageDeclaration extends Element<PackageDeclaration> implements QualifiedNameable {

    private final List<AnnotationTree> annotations = new ArrayList<>();

    private final String packageName;

    private PackageElement packageElement;

    public PackageDeclaration(final List<AnnotationTree> annotations,
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

    public PackageElement getPackageElement() {
        return packageElement;
    }

    public void setPackageElement(final PackageElement packageElement) {
        this.packageElement = packageElement;
    }

    @Override
    public String getQualifiedName() {
        return packageName;
    }

    @Override
    protected PackageDeclaration self() {
        return this;
    }
}
