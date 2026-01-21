package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;

import java.util.ArrayList;
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
        switch (tree) {
            case CompilationUnit compilationUnit -> {
                return compilationUnit.getClasses();
            }
            case ClassDeclaration classDeclaration -> {
                return classDeclaration.getEnclosedElements();
            }
            case MethodInvocationTree methodInvocationTree -> {
                return subTrees(methodInvocationTree.getMethodSelector());
            }
            case FieldAccessExpressionTree fieldAccessExpressionTree -> {
                final var subtrees = new ArrayList<Tree>(subTrees(fieldAccessExpressionTree.getSelected()));
                subtrees.add(fieldAccessExpressionTree.getField());
                return subtrees;
            }
            case IdentifierTree identifierTree -> {
                return List.of(identifierTree);
            }
            case null, default -> {
                return Collections.emptyList();
            }
        }
    }

    private static String getName(final Tree tree) {
        return switch (tree) {
            case ClassDeclaration classDeclaration -> classDeclaration.getSimpleName();
            case io.github.potjerodekool.nabu.tree.element.Function function -> function.getSimpleName();
            case IdentifierTree identifierTree -> identifierTree.getName();
            case null, default -> "";
        };
    }
}

