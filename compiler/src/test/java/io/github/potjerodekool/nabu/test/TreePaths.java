package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;

import java.util.Collections;
import java.util.List;

public class TreePaths {

    public static <T extends Tree> T select(final Tree tree,
                                            final String path) {
        return (T) select(tree, path.split("\\."), 0);
    }

    private static Tree select(final Tree tree,
                               final String[] pathElements,
                               final int index) {
        final var pathElement = pathElements[index];
        final var subTrees = subTrees(tree);
        final var subTreeOptional = subTrees.stream()
                .filter(it -> pathElement.equals(getName(it)))
                .findFirst();

        if (subTreeOptional.isPresent()) {
            final var subTree = subTreeOptional.get();

            if (index < pathElements.length - 1) {
                return select(subTree, pathElements, index + 1);
            } else {
                return subTree;
            }
        } else {
            return null;
        }
    }

    private static List<? extends Tree> subTrees(final Tree tree) {
        if (tree instanceof CompilationUnit compilationUnit) {
            return compilationUnit.getClasses();
        } else if (tree instanceof ClassDeclaration classDeclaration) {
            return classDeclaration.getEnclosedElements();
        } else {
            return Collections.emptyList();
        }
    }

    private static String getName(final Tree tree) {
        if (tree instanceof ClassDeclaration classDeclaration) {
            return classDeclaration.getSimpleName();
        } else if (tree instanceof io.github.potjerodekool.nabu.tree.element.Function function) {
            return function.getSimpleName();
        } else {
            return "";
        }
    }
}

