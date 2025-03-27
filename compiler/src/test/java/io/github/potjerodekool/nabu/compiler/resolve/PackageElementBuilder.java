package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.symbol.PackageSymbol;

import java.util.Objects;

public final class PackageElementBuilder {

    private PackageElementBuilder() {
    }

    public static PackageSymbol createFromName(final String name) {
        if (name.indexOf('/') > -1) {
            return createFromName(name, "/");
        } else {
            return createFromName(name, "\\.");
        }
    }

    private static PackageSymbol createFromName(final String name,
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
