package io.github.potjerodekool.nabu.plugin.jpa.transform;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementFilter;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public class JpaElementResolver implements ElementResolver {

    private static final String PATH_CLASS = "jakarta.persistence.criteria.Path";

    private static final String JOIN_CLASS = "jakarta.persistence.criteria.Join";

    private DeclaredType pathType;

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

    private TypeMirror getPathType(final CompilerContext compilerContext,
                                   final Scope scope) {
        if (pathType == null) {
            pathType = (DeclaredType) compilerContext.getClassElementLoader().loadClass(
                    scope.findModuleElement(),
                    PATH_CLASS
            ).asType();
        }
        return pathType;
    }

    @Override
    public boolean supports(final TypeMirror searchType,
                            final CompilerContext compilerContext,
                            final Scope scope) {
        if (!(searchType instanceof DeclaredType)) {
            return false;
        }

        final var pathType = getPathType(compilerContext, scope);
        final var types = compilerContext.getClassElementLoader().getTypes();
        return types.isSubType(searchType, pathType);
    }
}
