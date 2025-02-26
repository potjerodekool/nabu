package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.ast.element.*;

import java.util.List;
import java.util.Optional;

public final class ElementFilter {

    private ElementFilter() {
    }

    private static <E extends Element> List<E> elements(final TypeElement classSymbol,
                                                        final ElementKind kind,
                                                        final Class<E> type) {
        return classSymbol.getEnclosedElements().stream()
                .filter(it -> it.getKind() == kind)
                .map(type::cast)
                .toList();
    }

    public static List<VariableElement> fields(final TypeElement classSymbol) {
        return elements(classSymbol, ElementKind.FIELD, VariableElement.class);
    }

    public static List<ExecutableElement> methods(final TypeElement classSymbol) {
        return elements(classSymbol, ElementKind.METHOD, ExecutableElement.class);
    }

    public static List<ExecutableElement> constructors(final TypeElement classSymbol) {
        return elements(classSymbol, ElementKind.CONSTRUCTOR, ExecutableElement.class);
    }

    public static List<VariableElement> enumValues(final TypeElement classSymbol) {
        return elements(classSymbol, ElementKind.ENUM_CONSTANT, VariableElement.class);
    }

    public static Optional<VariableElement> fieldByName(final TypeElement classSymbol,
                                                        final String name) {
        return fields(classSymbol).stream()
                .filter(it -> it.getSimpleName().equals(name)).findFirst();
    }

    public static Optional<VariableElement> enumConstantByName(final TypeElement classSymbol,
                                                               final String name) {
        return enumValues(classSymbol).stream()
                .filter(it -> it.getSimpleName().equals(name)).findFirst();
    }
}
