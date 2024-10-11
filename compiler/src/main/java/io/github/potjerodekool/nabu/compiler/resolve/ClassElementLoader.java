package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

import java.nio.file.Path;

public interface ClassElementLoader {
    ClassSymbol resolveClass(String name);

    TypeMirror resolveType(String name);

    Types getTypes();

    void postInit();

    void addClassPathEntry(Path path);

    PackageElement findOrCreatePackage(String packageName);

    void importJavaLang(ImportScope importScope);
}
