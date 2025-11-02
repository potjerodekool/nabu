package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.util.CollectionUtils;

import java.util.List;
import java.util.stream.Stream;

/**
 * Filter utilities for trees.
 */
public final class TreeFilter {

    private TreeFilter() {
    }

    /**
     * @param enclosedElements A list of elements
     * @return Returns a list of field elements from the given list.
     */
    public static List<VariableDeclaratorTree> fieldsIn(final List<Tree> enclosedElements) {
        return filter(enclosedElements, VariableDeclaratorTree.class)
                .filter(f -> f.getKind() == Kind.FIELD)
                .toList();
    }

    /**
     * @param enclosedElements A list of elements.
     * @return Returns a list of enum consTANTS from the given list.
     */
    public static List<VariableDeclaratorTree> enumConstantsIn(final List<Tree> enclosedElements) {
        return filter(enclosedElements, VariableDeclaratorTree.class)
                .filter(f -> f.getKind() == Kind.ENUM_CONSTANT)
                .toList();
    }

    /**
     * @param enclosedElements A list of element.
     * @return Returns a list of constructors from the given list.
     */
    public static List<Function> constructorsIn(final List<Tree> enclosedElements) {
        return filter(enclosedElements, Function.class)
                .filter(f -> f.getKind() == Kind.CONSTRUCTOR)
                .toList();
    }

    /**
     * @param enclosedElements A list of elements.
     * @return Returns a list of methods from the given list.
     */
    public static List<Function> methodsIn(final List<Tree> enclosedElements) {
        return filter(enclosedElements, Function.class)
                .filter(f -> f.getKind() == Kind.METHOD)
                .toList();
    }

    /**
     * @param list A list of elements.
     * @return Returns a list of classes from the given list.
     */
    public static List<ClassDeclaration> classesIn(final List<Tree> list) {
        return filter(list, ClassDeclaration.class)
                .toList();
    }

    /**
     * @param list A list of trees.
     * @param filterType A type to filter on.
     * @return Returns a list of trees that match the filterType.
     * @param <E> The type to filter on.
     */
    private static <E> Stream<E> filter(final List<Tree> list,
                                        final Class<E> filterType) {
        return list.stream()
                .flatMap(CollectionUtils.mapOnly(filterType));
    }
}
