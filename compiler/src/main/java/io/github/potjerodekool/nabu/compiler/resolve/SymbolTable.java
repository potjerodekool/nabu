package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.PackageElementBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.Symbol;

import java.util.HashMap;
import java.util.Map;

public class SymbolTable {

    private final Map<String, TypeElement> classes = new HashMap<>();
    private final Map<String, PackageSymbol> packages = new HashMap<>();


    public TypeElement getClassSymbol(final String internalName) {
        return classes.get(internalName);
    }

    public void addClassSymbol(final String internalName,
                               final TypeElement type) {
        this.classes.put(internalName, type);
    }

    public PackageSymbol findPackage(final String packageName) {
        return packages.get(packageName);
    }

    public PackageElement findOrCreatePackage(final String packageName) {
        var packageElement = packages.get(packageName);

        if (packageElement != null) {
            return packageElement;
        }

        final var elements = packageName.split("\\.");

        for (final String name : elements) {
            packageElement = doFindOrCreatePackage(packageElement, name);
        }

        return packageElement;
    }

    private PackageSymbol doFindOrCreatePackage(final PackageSymbol parentPackage,
                                                 final String packageName) {
        var packageElement = packages.get(packageName);

        if (packageElement == null) {
            packageElement = PackageElementBuilder.create(parentPackage, packageName);

            if (parentPackage != null) {
                parentPackage.addEnclosedElement(packageElement);
            }

            packages.put(packageElement.getQualifiedName(), packageElement);
        }

        return packageElement;
    }
}
