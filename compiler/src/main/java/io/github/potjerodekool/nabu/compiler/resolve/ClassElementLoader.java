package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.ModuleElement;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.util.Types;

public interface ClassElementLoader extends AutoCloseable {

    TypeElement loadClass(ModuleElement moduleElement,
                          String name);

    Types getTypes();

    PackageElement findOrCreatePackage(final ModuleElement moduleElement,
                                       String packageName);

    void importJavaLang(ImportScope importScope);

}
