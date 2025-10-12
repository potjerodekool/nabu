package io.github.potjerodekool.nabu.resolve;

import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.util.Types;

public interface ClassElementLoader extends AutoCloseable {

    TypeElement loadClass(ModuleElement moduleElement,
                          String name);

    Types getTypes();

    PackageElement findOrCreatePackage(final ModuleElement moduleElement,
                                       String packageName);

    void importJavaLang(ImportScope importScope);

}
