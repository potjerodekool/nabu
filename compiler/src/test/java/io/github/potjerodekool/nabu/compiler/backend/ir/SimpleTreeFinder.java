package io.github.potjerodekool.nabu.compiler.backend.ir;


import io.github.potjerodekool.nabu.testing.TreeWalker;
import io.github.potjerodekool.nabu.tree.Tree;

import java.util.function.Predicate;

public class SimpleTreeFinder {

    public Tree find(final Tree start,
                     final Predicate<Tree> predicate) {
        final var result = new Result();

        TreeWalker.walk(start, tree -> {
            if (predicate.test(tree)) {
                result.tree = tree;
            }
        });

        return result.tree;
    }
}

class Result {
    Tree tree;
}