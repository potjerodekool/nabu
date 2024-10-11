package io.github.potjerodekool.nabu.compiler.tree.element;

import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.QualifiedNameable;
import io.github.potjerodekool.nabu.compiler.tree.TreeVisitor;

public class CClassDeclaration extends CElement<CClassDeclaration> implements QualifiedNameable {

    private String qualifiedName;

    public ClassSymbol classSymbol;

    @Override
    public <R, P> R accept(final TreeVisitor<R,P> visitor, final P param) {
        return visitor.visitClass(this, param);
    }

    @Override
    public CClassDeclaration enclosingElement(final CElement<?> enclosingElement) {
        this.qualifiedName = null;
        return super.enclosingElement(enclosingElement);
    }

    @Override
    public String getQualifiedName() {
        if (qualifiedName == null) {
            final var enclossing = getEnclosingElement();

            if (enclossing == null) {
                qualifiedName = getSimpleName();
            } else if (enclossing instanceof QualifiedNameable qualifiedNameable) {
                final var enclosingName = qualifiedNameable.getQualifiedName();
                final var simpleName = getSimpleName();
                qualifiedName = enclosingName + "." + simpleName;
            }
        }

        return qualifiedName;
    }
}
