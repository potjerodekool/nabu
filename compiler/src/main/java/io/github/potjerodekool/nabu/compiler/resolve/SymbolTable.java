package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;

import java.util.HashMap;
import java.util.Map;

public class SymbolTable {

    private final Map<String, ClassSymbol> classes = new HashMap<>();
    private final Map<String, PackageElement> packages = new HashMap<>();


    public ClassSymbol getClassSymbol(final String internalName) {
        return classes.get(internalName);
    }

    public void addClassSymbol(final String internalName,
                               final ClassSymbol type) {
        this.classes.put(internalName, type);
    }

    public PackageElement findPackage(final String packageName) {
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

    private PackageElement doFindOrCreatePackage(final PackageElement parentPackage,
                                                 final String packageName) {
        var packageElement = packages.get(packageName);

        if (packageElement != null) {
            return packageElement;
        } else {
            packageElement = new PackageElement(parentPackage, packageName);

            if (parentPackage != null) {
                parentPackage.addEnclosedElement(packageElement);
            }

            packages.put(packageElement.getQualifiedName(), packageElement);
            return packageElement;
        }
    }
}
