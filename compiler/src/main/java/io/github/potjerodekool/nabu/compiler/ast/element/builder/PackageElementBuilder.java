package io.github.potjerodekool.nabu.compiler.ast.element.builder;

import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.PackageSymbol;

import java.util.Objects;

public final class PackageElementBuilder {

    private PackageElementBuilder() {
    }

    public static PackageElement create(final PackageElement parentPackage,
                                        final String packageName) {
        return new PackageSymbol(parentPackage, packageName);
    }

    public static PackageElement createFromName(final String name) {
        if (name.indexOf('/') > -1) {
            return createFromName(name, "/");
        } else {
            return createFromName(name, "\\.");
        }
    }

    private static PackageElement createFromName(final String name,
                                                 final String separator) {
        final var elements = name.split(separator);
        PackageSymbol packageElement = null;

        for (final var element : elements) {
            packageElement = new PackageSymbol(packageElement, element);
        }

        Objects.requireNonNull(packageElement);

        return packageElement;
    }

}
