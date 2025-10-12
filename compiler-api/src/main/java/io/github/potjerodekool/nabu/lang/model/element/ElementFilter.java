package io.github.potjerodekool.nabu.lang.model.element;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class ElementFilter {

    private static final Set<ElementKind> TYPE_KINDS =
            Stream.of(ElementKind.CLASS,
                            ElementKind.ENUM,
                            ElementKind.INTERFACE,
                            ElementKind.RECORD,
                            ElementKind.ANNOTATION_TYPE)
                    .collect(Collectors.toUnmodifiableSet());

    private static final Set<ElementKind> RECORD_COMPONENT_KIND = Set.of(ElementKind.RECORD_COMPONENT);

    private ElementFilter() {
    }

    private static <E extends Element> List<E> elements(final TypeElement classSymbol,
                                                        final ElementKind kind,
                                                        final Class<E> type) {
        return elements(classSymbol, element -> element.getKind() == kind, type);
    }

    public static <E extends Element> List<E> elements(final TypeElement classSymbol,
                                                       final Predicate<Element> filter,
                                                       final Class<E> type) {
        return classSymbol.getEnclosedElements().stream()
                .filter(filter)
                .map(type::cast)
                .toList();
    }

    public static List<VariableElement> fieldsIn(final Iterable<? extends Element> elements) {
        return listFilter(elements, Set.of(ElementKind.FIELD), VariableElement.class);
    }

    public static List<ExecutableElement> methodsIn(final Iterable<? extends Element> elements) {
        return listFilter(elements, Set.of(ElementKind.METHOD), ExecutableElement.class);
    }

    public static List<ExecutableElement> constructorsIn(final Iterable<? extends Element> elements) {
        return listFilter(elements, Set.of(ElementKind.CONSTRUCTOR), ExecutableElement.class);
    }

    public static List<VariableElement> enumValuesIn(final List<? extends Element> elements) {
        return listFilter(elements, Set.of(ElementKind.ENUM_CONSTANT), VariableElement.class);
    }

    public static Optional<VariableElement> enumConstantByName(final TypeElement classSymbol,
                                                               final String name) {
        return enumValuesIn(classSymbol.getEnclosedElements()).stream()
                .filter(it -> it.getSimpleName().equals(name)).findFirst();
    }

    public static List<TypeElement> typesIn(final Iterable<? extends Element> elements) {
        return listFilter(elements, TYPE_KINDS, TypeElement.class);
    }

    private static <E extends Element> List<E> listFilter(final Iterable<? extends Element> elements,
                                                          final Set<ElementKind> targetKinds,
                                                          final Class<E> clazz) {
        final var list = StreamSupport.stream(elements.spliterator(), false)
                .toList();

        return list.stream()
                .filter(e -> targetKinds.contains(e.getKind()))
                .map(clazz::cast)
                .toList();
    }

    public static List<RecordComponentElement> recordComponentsIn(final Iterable<? extends Element> elements) {
        return listFilter(elements, RECORD_COMPONENT_KIND, RecordComponentElement.class);
    }
}
