package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class TreeFilter {

    private TreeFilter() {
    }

    public static List<VariableDeclaratorTree> fieldsIn(final List<Tree> enclosedElements) {
        return filter(enclosedElements, VariableDeclaratorTree.class)
                .toList();
    }

    public static List<VariableDeclaratorTree> fields(final ClassDeclaration classDeclaration) {
        return filter(classDeclaration, VariableDeclaratorTree.class, f -> f.getKind() == Kind.FIELD)
                .toList();
    }
    public static List<VariableDeclaratorTree> enumConstants(final ClassDeclaration classDeclaration) {
        return filter(classDeclaration, VariableDeclaratorTree.class, f -> f.getKind() == Kind.ENUM_CONSTANT)
                .toList();
    }

    public static List<Function> constructors(final ClassDeclaration classDeclaration) {
        return filter(classDeclaration, Function.class, f -> f.getKind() == Kind.CONSTRUCTOR)
                .toList();
    }

    public static List<Function> methods(final ClassDeclaration classDeclaration) {
        return filter(classDeclaration, Function.class, f -> f.getKind() == Kind.METHOD)
                .toList();
    }

    public static List<ClassDeclaration> classesIn(final List<Tree> list) {
        return filter(list, ClassDeclaration.class)
                .toList();
    }

    private static <E> Stream<E> filter(final ClassDeclaration classDeclaration,
                                        final Class<E> filterType,
                                        final Predicate<E> additionalFilter) {
        return filter(classDeclaration, filterType)
                .filter(additionalFilter);
    }

    private static <E> Stream<E> filter(final ClassDeclaration classDeclaration,
                                        final Class<E> filterType) {
        return classDeclaration.getEnclosedElements().stream()
                .flatMap(CollectionUtils.mapOnly(filterType));
    }

    private static <E> Stream<E> filter(final List<Tree> list,
                                        final Class<E> filterType) {
        return filter(list, filterType, it -> true);
    }

    private static <E> Stream<E> filter(final List<Tree> list,
                                        final Class<E> filterType,
                                        final Predicate<E> additionalFilter) {
        return list.stream()
                .flatMap(CollectionUtils.mapOnly(filterType));
    }
}
