package io.github.potjerodekool.nabu.resolve;

import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.PackageElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.util.Types;

/**
 * A loader for classes.
 */
public interface ClassElementLoader extends AutoCloseable {

    /**
     * @param moduleElement A module
     * @param name a class name.
     * @return Returns the class with the given name without the given module or null.
     */
    TypeElement loadClass(ModuleElement moduleElement,
                          String name);

    /**
     * @param moduleElement A module.
     * @param packageName A package name.
     * @return Returns the package from the given module.
     */
    PackageElement findOrCreatePackage(ModuleElement moduleElement,
                                       String packageName);

    /**
     * @param importScope ImportScope.
     * Import the classes from the java.lang package into the scope.
     */
    void importJavaLang(ImportScope importScope);

}
