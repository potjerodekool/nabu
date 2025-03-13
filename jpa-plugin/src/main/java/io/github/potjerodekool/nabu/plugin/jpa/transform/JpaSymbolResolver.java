package io.github.potjerodekool.nabu.plugin.jpa.transform;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.ElementFilter;
import io.github.potjerodekool.nabu.compiler.resolve.SymbolResolver;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.Types;

public class JpaSymbolResolver implements SymbolResolver {

    private static final String PATH_CLASS = "jakarta.persistence.criteria.Path";

    private static final String JOIN_CLASS = "jakarta.persistence.criteria.Join";

    private final ClassElementLoader loader;
    private final Types types;

    private DeclaredType pathType;

    public JpaSymbolResolver(final ClassElementLoader loader) {
        this.loader = loader;
        this.types = loader.getTypes();
    }

    @Override
    public Element resolve(final String name,
                           final TypeMirror searchType) {
        final var classType = (DeclaredType) searchType;
        final DeclaredType parameterType;

        if (isJoinType(classType)) {
            parameterType = (DeclaredType) classType.getTypeArguments().getLast();
        } else {
            parameterType = (DeclaredType) classType.getTypeArguments().getFirst();
        }

        final var classSymbol = (TypeElement) parameterType.asElement();
        return ElementFilter.fields(classSymbol).stream()
                .filter(elem -> elem.getSimpleName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private boolean isJoinType(final DeclaredType declaredType) {
        final var classSymbol = (TypeElement) declaredType.asElement();
        return JOIN_CLASS.equals(classSymbol.getQualifiedName());
    }

    private TypeMirror getPathType() {
        if (pathType == null) {
            pathType = (DeclaredType) loader.loadClass(PATH_CLASS).asType();
        }
        return pathType;
    }

    @Override
    public boolean supports(final TypeMirror searchType) {
        if (!(searchType instanceof DeclaredType)) {
            return false;
        }

        final var pathType = getPathType();
        return types.isSubType(searchType, pathType);
    }
}
