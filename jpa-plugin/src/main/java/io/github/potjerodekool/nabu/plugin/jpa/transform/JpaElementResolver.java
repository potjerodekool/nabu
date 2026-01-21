package io.github.potjerodekool.nabu.plugin.jpa.transform;

import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

/**
 * Resolve elements on Path instances.
 * <p>
 * final var personRoot : Root&lt;Person&gt;
 * //Access the firstName of Person class
 * personRoot.firstName
 */
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
        return ElementFilter.fieldsIn(classSymbol.getEnclosedElements()).stream()
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
        final var types = compilerContext.getTypes();
        return types.isSubType(searchType, pathType);
    }
}
