package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;

public class Resolver {

    private final ClassElementLoader classElementLoader;
    private final ImportScope importScope;

    public Resolver(final ClassElementLoader classElementLoader,
                    final ImportScope importScope) {
        this.classElementLoader = classElementLoader;
        this.importScope = importScope;
    }

    public Element resolveClass(final String name) {
        var clazz = classElementLoader.resolveClass(name);

        if (clazz == null) {
            return resolveInImportScope(name);
        } else {
            return clazz;
        }
    }

    private TypeElement resolveInImportScope(final String name) {
        var clazz = (TypeElement) importScope.resolve(name);

        if (clazz == null && !name.contains(".")) {
            clazz = (TypeElement) importScope.resolve("java.lang." + name);
        }

        return clazz;
    }
}
