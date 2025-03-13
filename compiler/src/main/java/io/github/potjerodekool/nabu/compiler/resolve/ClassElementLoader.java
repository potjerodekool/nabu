package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.type.Types;

import java.nio.file.Path;

public interface ClassElementLoader {
    TypeElement loadClass(String name);

    Types getTypes();

    void postInit();

    void addClassPathEntry(Path path);

    PackageElement findOrCreatePackage(String packageName);

    void importJavaLang(ImportScope importScope);

    void close();
}
