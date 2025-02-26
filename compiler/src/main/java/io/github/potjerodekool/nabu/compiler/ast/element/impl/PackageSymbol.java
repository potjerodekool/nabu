package io.github.potjerodekool.nabu.compiler.ast.element.impl;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;

import java.util.Set;

public class PackageSymbol extends TypeSymbol implements PackageElement {

    private static final PackageElement UNNAMED_PACKAGE = new PackageSymbol(
            null,
            ""
    );

    private String qualifiedName;

    public PackageSymbol(final PackageElement parentPackage,
                         final String packageName) {
        super(ElementKind.PACKAGE, Set.of(), packageName, parentPackage);
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
        throw new TodoException();
    }

    @Override
    public boolean isUnnamed() {
        return this == UNNAMED_PACKAGE;
    }
}
