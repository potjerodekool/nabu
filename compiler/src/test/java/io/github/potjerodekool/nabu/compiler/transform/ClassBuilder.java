package io.github.potjerodekool.nabu.compiler.transform;

import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.NestingKind;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

public class ClassBuilder {

    private String name;
    private ElementKind kind = ElementKind.CLASS;
    private NestingKind nestingKind = NestingKind.TOP_LEVEL;

    private final List<TypeMirror> interfaces = new ArrayList<>();

    public ClassBuilder name(final String name) {
        this.name = name;
        return this;
    }

    public ClassBuilder kind(final ElementKind kind) {
        this.kind = kind;
        return this;
    }

    public ClassBuilder nestingKind(final NestingKind nestingKind) {
        this.nestingKind = nestingKind;
        return this;
    }

    public ClassBuilder interfaceType(final TypeMirror typeMirror) {
        this.interfaces.add(typeMirror);
        return this;
    }

    public ClassSymbol build() {
        final var packageEnd = name.lastIndexOf(".");
        final PackageElement packageElement;
        final String simpleName;

        if (packageEnd > 0) {
            packageElement = createPackage(name.substring(0, packageEnd));
            simpleName = name.substring(packageEnd + 1);
        } else {
            packageElement = null;
            simpleName = name;
        }

        final var clazz = new ClassSymbol(
                kind,
                nestingKind,
                simpleName,
                packageElement);
        clazz.setEnclosingElement(packageElement);

        interfaces.forEach(clazz::addInterface);
        return clazz;
    }

    private PackageElement createPackage(final String packageName) {
        PackageElement packageElement = null;
        final var names = packageName.split("\\.");

        for (final String name : names) {
            packageElement = new PackageElement(
                    packageElement,
                    name
            );
        }

        return packageElement;
    }
}
