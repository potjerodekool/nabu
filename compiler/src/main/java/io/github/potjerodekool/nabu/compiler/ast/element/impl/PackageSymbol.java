package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.ast.element.*;

public class PackageSymbol extends TypeSymbol implements PackageElement {

    private static final PackageElement UNNAMED_PACKAGE = new PackageSymbol(
            null,
            ""
    );

    private String qualifiedName;

    public PackageSymbol(final PackageSymbol parentPackage,
                         final String packageName) {
        super(ElementKind.PACKAGE, 0, packageName, parentPackage);
        if (packageName.contains(".")) {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public String getQualifiedName() {
        if (qualifiedName != null) {
            return qualifiedName;
        }

        final var parentPackage = (PackageElement) getEnclosingElement();

        if (parentPackage == null) {
            qualifiedName = getSimpleName();
        } else {
            final var parentName = parentPackage.getQualifiedName();
            qualifiedName = parentName + "." + getSimpleName();
        }
        return qualifiedName;
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitPackage(this, p);
    }

    @Override
    public <R, P> R accept(final SymbolVisitor<R, P> v, final P p) {
        return v.visitPackage(this, p);
    }

    @Override
    public boolean isUnnamed() {
        return this == UNNAMED_PACKAGE;
    }
}
