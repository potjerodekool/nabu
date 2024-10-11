package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.QualifiedNameable;
import io.github.potjerodekool.nabu.compiler.tree.element.CElement;

public class CPackageDeclaration extends CElement<CPackageDeclaration> implements QualifiedNameable {

    private final String packageName;

    private PackageElement packageElement;

    public CPackageDeclaration(final String packageName) {
        this.packageName = packageName;
    }

    public String getPackageName() {
        return packageName;
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
}
