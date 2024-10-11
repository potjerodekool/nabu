package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class Resolver {

    private final ClassElementLoader classElementLoader;
    private final ImportScope importScope;

    public Resolver(final ClassElementLoader classElementLoader,
                    final ImportScope importScope) {
        this.classElementLoader = classElementLoader;
        this.importScope = importScope;
    }

    public TypeMirror resolveType(final String name) {
        var type = classElementLoader.resolveType(name);

        if (type == null) {
            final var clazz = resolveInImportScope(name);

            if (clazz != null) {
                type = classElementLoader.getTypes()
                        .getDeclaredType(clazz);
            }
        }

        return type;
    }

    private TypeElement resolveInImportScope(final String name) {
        var clazz = (TypeElement) importScope.resolve(name);

        if (clazz == null && !name.contains(".")) {
            clazz = (TypeElement) importScope.resolve("java.lang." + name);
        }

        return clazz;
    }
}
