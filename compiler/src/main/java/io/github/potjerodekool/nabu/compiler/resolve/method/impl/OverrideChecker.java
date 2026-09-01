package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;

import java.util.List;

/**
 * Checks if a method overrides another method according to JLS 8.4.8.1.
 */
public final class OverrideChecker {

    private final Types types;

    public OverrideChecker(final Types types) {
        this.types = types;
    }

    /**
     * Checks whether {@code overrider} overrides {@code overridden}
     * when viewed from class {@code type}.
     *
     * <p>Per JLS 8.4.8.1, an instance method m1 overrides m2 if:
     * <ul>
     *   <li>m1 has the same name as m2
     *   <li>m1 has the same number and types of parameters as m2 (after erasure)
     *   <li>m1 is not static
     *   <li>m1 is not private
     *   <li>m2 is not private, not final
     *   <li>The return type of m1 is a subtype of the return type of m2
     *   <li>The class declaring m1 is a subclass of the class declaring m2
     * </ul>
     *
     * @param overrider  the candidate overriding method
     * @param overridden the candidate overridden method
     * @param type       the type from which the override is checked
     * @return true if {@code overrider} overrides {@code overridden}
     */
    public boolean overrides(final ExecutableElement overrider,
                              final ExecutableElement overridden,
                              final TypeElement type) {
        if (overrider == overridden) {
            return false;
        }

        if (!overrider.getSimpleName().equals(overridden.getSimpleName())) {
            return false;
        }

        if (overrider.isStatic()) {
            return false;
        }

        if (overridden.isPrivate()) {
            return false;
        }

        if (overridden.isFinal()) {
            return false;
        }

        if (!hasSameParameterTypes(overrider, overridden)) {
            return false;
        }

        if (!isReturnTypeCompatible(overrider, overridden)) {
            return false;
        }

        if (!isAccessibleFrom(overridden, overrider)) {
            return false;
        }

        final var overriderClass = findEnclosingType(overrider);
        final var overriddenClass = findEnclosingType(overridden);

        if (overriderClass == null || overriddenClass == null) {
            return false;
        }

        return isSubclass(overriderClass, overriddenClass);
    }

    private boolean hasSameParameterTypes(final ExecutableElement m1,
                                           final ExecutableElement m2) {
        final var params1 = getErasedParameterTypes(m1);
        final var params2 = getErasedParameterTypes(m2);

        if (params1.size() != params2.size()) {
            return false;
        }

        for (int i = 0; i < params1.size(); i++) {
            if (!types.isSameType(params1.get(i), params2.get(i))) {
                return false;
            }
        }

        return true;
    }

    private List<? extends TypeMirror> getErasedParameterTypes(final ExecutableElement method) {
        final var methodType = (ExecutableType) method.asType();
        return methodType.getParameterTypes().stream()
                .map(types::erasure)
                .toList();
    }

    private boolean isReturnTypeCompatible(final ExecutableElement overrider,
                                            final ExecutableElement overridden) {
        final var overriderReturn = overrider.getReturnType();
        final var overriddenReturn = overridden.getReturnType();

        if (overriderReturn == null || overriddenReturn == null) {
            return true;
        }

        if (types.isSameType(overriderReturn, overriddenReturn)) {
            return true;
        }

        return types.isSubType(overriderReturn, overriddenReturn);
    }

    private boolean isAccessibleFrom(final ExecutableElement overridden,
                                      final ExecutableElement overrider) {
        if (overridden.isPublic()) {
            return true;
        }

        if (overridden.isProtected()) {
            return true;
        }

        final var overriddenPackage = overridden.getPackageElement();
        final var overriderPackage = overrider.getPackageElement();

        if (overriddenPackage != null && overriderPackage != null) {
            return overriddenPackage.getQualifiedName().equals(overriderPackage.getQualifiedName());
        }

        return false;
    }

    private TypeElement findEnclosingType(final ExecutableElement method) {
        Element enclosing = method.getEnclosingElement();

        while (enclosing != null) {
            if (enclosing instanceof TypeElement typeElement) {
                return typeElement;
            }
            enclosing = enclosing.getEnclosingElement();
        }

        return null;
    }

    private boolean isSubclass(final TypeElement subClass, final TypeElement superClass) {
        if (subClass == superClass) {
            return true;
        }

        return ((Symbol) subClass)
                .isSubClass(
                        (Symbol) superClass,
                        types
                );
    }
}
