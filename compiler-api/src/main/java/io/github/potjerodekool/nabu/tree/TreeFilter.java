package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.util.CollectionUtils;

import java.util.List;
import java.util.stream.Stream;

public final class TreeFilter {

    private TreeFilter() {
    }

    public static List<VariableDeclaratorTree> fieldsIn(final List<Tree> enclosedElements) {
        return filter(enclosedElements, VariableDeclaratorTree.class)
                .filter(f -> f.getKind() == Kind.FIELD)
                .toList();
    }

    public static List<VariableDeclaratorTree> enumConstantsIn(final List<Tree> enclosedElements) {
        return filter(enclosedElements, VariableDeclaratorTree.class)
                .filter(f -> f.getKind() == Kind.ENUM_CONSTANT)
                .toList();
    }

    public static List<Function> constructorsIn(final List<Tree> enclosedElements) {
        return filter(enclosedElements, Function.class)
                .filter(f -> f.getKind() == Kind.CONSTRUCTOR)
                .toList();
    }

    public static List<Function> methodsIn(final List<Tree> enclosedElements) {
        return filter(enclosedElements, Function.class)
                .filter(f -> f.getKind() == Kind.METHOD)
                .toList();
    }

    public static List<ClassDeclaration> classesIn(final List<Tree> list) {
        return filter(list, ClassDeclaration.class)
                .toList();
    }

    private static <E> Stream<E> filter(final List<Tree> list,
                                        final Class<E> filterType) {
        return list.stream()
                .flatMap(CollectionUtils.mapOnly(filterType));
    }
}
